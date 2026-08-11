package com.sparrowlogic.grafanamcp.analysis;

import com.sparrowlogic.grafanamcp.client.Times;
import com.sparrowlogic.grafanamcp.config.TempoProperties;
import com.sparrowlogic.grafanamcp.domain.ParsedTrace;
import com.sparrowlogic.grafanamcp.domain.TraceSpan;
import com.sparrowlogic.grafanamcp.domain.TraceSummary;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns a parsed trace into the answers someone opens a trace to get: what determined the duration,
 * which service owns the time, where a failure started, and where the matching logs are.
 *
 * @see CriticalPath for why the timing analysis is not a sort by duration
 */
@Component
public class TraceAnalyzer {

    /**
     * Attributes that tend to explain a failure, in the order they are worth reading. Stack traces
     * are deliberately excluded — they are the largest attribute a span carries and are available
     * through a span listing when actually wanted.
     */
    private static final List<String> ERROR_DETAIL_KEYS = List.of(
            "exception.type", "exception.message", "error", "error.message",
            "otel.status_description", "http.response.status_code", "http.status_code",
            "rpc.grpc.status_code", "db.system.name", "db.query.text", "db.statement",
            "url.full", "http.route", "server.address");

    /**
     * Span attributes that double as Loki stream labels in this deployment, most selective first.
     * The OpenTelemetry collector here stamps spans with the same identifiers the log pipeline uses,
     * which is what makes a trace-to-logs handoff possible at all; a deployment that does not will
     * simply yield no suggestion rather than a wrong one.
     */
    private static final List<String> LOG_LABEL_KEYS = List.of(
            "service_name", "container", "project", "instance_id", "environment", "source",
            "log_source");

    private final TempoProperties properties;

    public TraceAnalyzer(TempoProperties properties) {
        this.properties = properties;
    }

    /** Summarises a trace. {@code exploreUrl} is carried through to the response. */
    public TraceSummary summarize(ParsedTrace trace, String exploreUrl) {
        TraceSpan root = trace.root();
        if (root == null || trace.spans().isEmpty()) {
            return this.empty(trace, exploreUrl);
        }
        long traceNs = Math.max(1L, trace.endNs() - trace.startNs());
        Map<String, Long> onPath = CriticalPath.of(trace);
        Map<String, Long> selfTime = CriticalPath.selfTime(trace);
        List<TraceSpan> failures = trace.spans().stream().filter(TraceSpan::failed).toList();
        return new TraceSummary(
                trace.traceId(),
                root.service(), root.name(), TraceSpan.toMillis(traceNs),
                Times.fromNanos(trace.startNs()).toString(),
                Times.fromNanos(trace.endNs()).toString(),
                trace.spans().size(), services(trace).size(), failures.size(),
                this.criticalPath(trace, onPath, traceNs),
                this.serviceTime(trace, onPath, selfTime, traceNs),
                this.errorOrigins(trace, failures),
                this.serviceCalls(trace),
                this.logSearch(trace, root),
                exploreUrl,
                this.notice(trace, failures));
    }

    private TraceSummary empty(ParsedTrace trace, String exploreUrl) {
        return new TraceSummary(trace.traceId(), null, null, 0d, null, null, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), null, exploreUrl,
                "No spans were returned for this trace id. Tempo only searches the blocks covering "
                        + "the requested window — widen 'from'/'until' around the trace's time, or "
                        + "check the id.");
    }

    /** The critical path in time order, so it reads as the sequence it was. */
    private List<TraceSummary.CriticalPathStep> criticalPath(ParsedTrace trace,
            Map<String, Long> onPath, long traceNs) {
        List<TraceSpan> onPathSpans = trace.spans().stream()
                .filter(span -> onPath.containsKey(span.spanId()))
                .sorted(Comparator.comparingLong(TraceSpan::startNs))
                .limit(this.properties.maxCriticalPathSteps())
                .toList();
        List<TraceSummary.CriticalPathStep> steps = new ArrayList<>();
        for (TraceSpan span : onPathSpans) {
            long nanos = onPath.getOrDefault(span.spanId(), 0L);
            steps.add(new TraceSummary.CriticalPathStep(span.spanId(), span.service(), span.name(),
                    span.kind(), TraceSpan.toMillis(nanos), percent(nanos, traceNs), span.failed()));
        }
        return List.copyOf(steps);
    }

    /**
     * Time per service. Ranked by critical-path contribution rather than self-time, because that is
     * the figure that adds up to the trace's duration and therefore the one that answers "where did
     * the latency go".
     */
    private List<TraceSummary.ServiceTime> serviceTime(ParsedTrace trace, Map<String, Long> onPath,
            Map<String, Long> selfTime, long traceNs) {
        Map<String, long[]> byService = new LinkedHashMap<>();
        for (TraceSpan span : trace.spans()) {
            long[] totals = byService.computeIfAbsent(span.service(), key -> new long[4]);
            totals[0] += onPath.getOrDefault(span.spanId(), 0L);
            totals[1] += selfTime.getOrDefault(span.spanId(), 0L);
            totals[2]++;
            if (span.failed()) {
                totals[3]++;
            }
        }
        return byService.entrySet().stream()
                .map(entry -> new TraceSummary.ServiceTime(entry.getKey(),
                        TraceSpan.toMillis(entry.getValue()[0]), percent(entry.getValue()[0], traceNs),
                        TraceSpan.toMillis(entry.getValue()[1]),
                        (int) entry.getValue()[2], (int) entry.getValue()[3]))
                .sorted(Comparator.comparingDouble(TraceSummary.ServiceTime::criticalPathMs).reversed())
                .limit(this.properties.maxServicesListed())
                .toList();
    }

    /**
     * Failures that started where they are reported. A span whose child also failed is only
     * relaying the status upward, and listing it alongside the real cause is what makes error
     * triage in a deep trace slow.
     */
    private List<TraceSummary.ErrorSpan> errorOrigins(ParsedTrace trace, List<TraceSpan> failures) {
        Map<String, List<TraceSpan>> children = trace.childrenBySpanId();
        return failures.stream()
                .filter(span -> children.getOrDefault(span.spanId(), List.of()).stream()
                        .noneMatch(TraceSpan::failed))
                .limit(this.properties.maxServicesListed())
                .map(span -> new TraceSummary.ErrorSpan(span.spanId(), span.service(), span.name(),
                        span.statusMessage(), errorDetail(span)))
                .toList();
    }

    private static Map<String, String> errorDetail(TraceSpan span) {
        Map<String, String> detail = new LinkedHashMap<>();
        for (String key : ERROR_DETAIL_KEYS) {
            String value = span.attributes().get(key);
            if (value != null && !value.isBlank()) {
                detail.put(key, value);
            }
        }
        return Map.copyOf(detail);
    }

    private List<TraceSummary.CallEdge> serviceCalls(ParsedTrace trace) {
        Map<String, TraceSpan> byId = new LinkedHashMap<>();
        trace.spans().forEach(span -> byId.put(span.spanId(), span));
        Map<String, int[]> edges = new LinkedHashMap<>();
        for (TraceSpan span : trace.spans()) {
            TraceSpan parent = span.parentSpanId() == null ? null : byId.get(span.parentSpanId());
            if (parent == null || parent.service().equals(span.service())) {
                continue;
            }
            int[] counts = edges.computeIfAbsent(parent.service() + ' ' + span.service(),
                    key -> new int[2]);
            counts[0]++;
            if (span.failed()) {
                counts[1]++;
            }
        }
        return edges.entrySet().stream()
                .map(entry -> {
                    String[] pair = entry.getKey().split(" ", 2);
                    return new TraceSummary.CallEdge(pair[0], pair[1], entry.getValue()[0],
                            entry.getValue()[1]);
                })
                .sorted(Comparator.comparingInt(TraceSummary.CallEdge::calls).reversed())
                .limit(this.properties.maxServicesListed())
                .toList();
    }

    /**
     * A log search aligned to this trace. The window is padded because log ingestion lags span
     * timestamps, and the selectors come from the span's own attributes rather than from guessing
     * that a Tempo service name is also a Loki label value — which it frequently is not.
     */
    private TraceSummary.LogSearchHint logSearch(ParsedTrace trace, TraceSpan root) {
        Duration padding = Duration.ofSeconds(this.properties.logWindowPaddingSeconds());
        String from = Times.fromNanos(trace.startNs()).minus(padding).toString();
        String until = Times.fromNanos(trace.endNs()).plus(padding).toString();
        Map<String, String> labels = new LinkedHashMap<>();
        for (String key : LOG_LABEL_KEYS) {
            String value = root.attributes().get(key);
            if (value != null && !value.isBlank()) {
                labels.put(key, value);
            }
        }
        List<String> queries = new ArrayList<>();
        labels.entrySet().stream().limit(2).forEach(label -> {
            String selector = "{" + label.getKey() + "=\"" + label.getValue() + "\"}";
            queries.add(selector + " |= `" + trace.traceId() + "`");
            queries.add(selector);
        });
        return new TraceSummary.LogSearchHint(from, until, List.copyOf(queries), Map.copyOf(labels),
                "Pass these to search_loki_logs with this from/until (padded, because log ingestion "
                        + "lags spans). Try the trace-id query first: it only matches where the "
                        + "service logs its trace id, and most services here do not — fall back to "
                        + "the plain selector, which is service-plus-window correlation.");
    }

    private String notice(ParsedTrace trace, List<TraceSpan> failures) {
        List<String> notes = new ArrayList<>();
        if (trace.spans().size() > this.properties.maxSpansDetail()) {
            notes.add("This trace has " + trace.spans().size() + " spans; get_trace_spans returns at "
                    + "most " + this.properties.maxSpansDetail() + " of them, so work from the "
                    + "critical path and error origins above rather than listing everything");
        }
        double selfTotal = CriticalPath.selfTime(trace).values().stream()
                .mapToLong(Long::longValue).sum() / 1_000_000d;
        if (selfTotal > TraceSpan.toMillis(trace.endNs() - trace.startNs()) * 1.05d) {
            notes.add("Summed self-time exceeds the trace duration because this instrumentation "
                    + "records concurrent work as overlapping sibling spans — read criticalPathMs, "
                    + "which partitions the duration, rather than summing selfMs");
        }
        if (!failures.isEmpty() && failures.size() > this.errorOrigins(trace, failures).size()) {
            notes.add("Of " + failures.size() + " failed spans, only the origins are listed — the "
                    + "rest inherited the status from a child");
        }
        return notes.isEmpty() ? null : String.join(". ", notes) + ".";
    }

    private static Set<String> services(ParsedTrace trace) {
        Set<String> services = new LinkedHashSet<>();
        trace.spans().forEach(span -> services.add(span.service()));
        return services;
    }

    private static double percent(long nanos, long traceNs) {
        return Math.round(nanos * 1000d / traceNs) / 10d;
    }
}
