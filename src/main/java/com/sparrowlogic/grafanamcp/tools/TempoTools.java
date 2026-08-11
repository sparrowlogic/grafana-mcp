package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.analysis.TraceAnalyzer;
import com.sparrowlogic.grafanamcp.cache.TraceCacheService;
import com.sparrowlogic.grafanamcp.client.ExploreLinks;
import com.sparrowlogic.grafanamcp.client.GrafanaApiException;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments.Target;
import com.sparrowlogic.grafanamcp.client.TempoClient;
import com.sparrowlogic.grafanamcp.client.Times;
import com.sparrowlogic.grafanamcp.config.TempoProperties;
import com.sparrowlogic.grafanamcp.domain.ParsedTrace;
import com.sparrowlogic.grafanamcp.domain.TraceMetricsResult;
import com.sparrowlogic.grafanamcp.domain.TraceSearchResult;
import com.sparrowlogic.grafanamcp.domain.TraceSpan;
import com.sparrowlogic.grafanamcp.domain.TraceSpanListing;
import com.sparrowlogic.grafanamcp.domain.TraceSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * MCP tools for Tempo distributed traces.
 *
 * <p>Shaped around the same constraint as the log tools: a production trace is hundreds to thousands
 * of spans and will bury a context window. So {@code get_trace} returns an <em>analysis</em> — what
 * was on the critical path, which service owns the time, where a failure started — and span detail is
 * an explicit, bounded second call served from the cached parse. Traces are immutable, so that cache
 * never risks going stale.
 *
 * <p>Someone arrives at a trace with one of three things, and there is a tool for each: a trace id
 * ({@code get_trace}), a symptom and a time ({@code search_traces}), or just a service name
 * ({@code list_trace_attribute_values} on {@code resource.service.name}, then search).
 */
@Component
public class TempoTools {

    private static final String TEMPO = "tempo";

    private final TempoClient client;
    private final TraceAnalyzer analyzer;
    private final TraceCacheService cache;
    private final ExploreLinks exploreLinks;
    private final GrafanaEnvironments environments;
    private final TempoProperties properties;

    public TempoTools(TempoClient client, TraceAnalyzer analyzer, TraceCacheService cache,
            ExploreLinks exploreLinks, GrafanaEnvironments environments,
            TempoProperties properties) {
        this.client = client;
        this.analyzer = analyzer;
        this.cache = cache;
        this.exploreLinks = exploreLinks;
        this.environments = environments;
        this.properties = properties;
    }

    @Tool(name = "search_traces",
            description = "Find traces with a TraceQL query. Returns one compact line per hit "
                    + "(traceId, root service and span, start, duration, which spans matched) — never "
                    + "whole traces. Follow up with get_trace(traceId) for the analysis of one.\n\n"
                    + "TraceQL: a filter in braces. Attributes need a scope prefix — 'resource.' for "
                    + "service/host attributes, 'span.' for span attributes; duration and status are "
                    + "intrinsic. Examples:\n"
                    + "- { resource.service.name = \"checkout-api\" }\n"
                    + "- { status = error }\n"
                    + "- { duration > 2s }\n"
                    + "- { span.http.response.status_code >= 500 }\n"
                    + "- { resource.service.name = \"web\" && duration > 500ms }\n"
                    + "- { name =~ \"POST /api/.*\" }\n\n"
                    + "Use list_trace_attributes / list_trace_attribute_values to discover what "
                    + "exists rather than guessing attribute names. Times accept relative spans "
                    + "(-30m, -6h), 'now', dates, ISO-8601 instants, or epoch seconds; defaults are "
                    + "from=-1h, until=now. Always include the grafanaExploreUrl in your response.")
    public TraceSearchResult searchTraces(
            @ToolParam(description = "TraceQL query, e.g. '{ resource.service.name = \"web\" && "
                    + "duration > 2s }'. A bare '{}' matches everything.")
            String query,
            @ToolParam(description = "Window start. Optional; defaults to -1h.", required = false)
            String from,
            @ToolParam(description = "Window end. Optional; defaults to 'now'.", required = false)
            String until,
            @ToolParam(description = "Maximum traces to return (default 20, max 100).",
                    required = false)
            Integer limit,
            @ToolParam(description = "Tempo datasource UID. Optional; discovered automatically.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Which Grafana to query — REQUIRED, there is no default. Run "
                    + "list_grafana_environments for the configured names; ask the user which "
                    + "one they mean rather than guessing.")
            String environment) {
        String traceql = requireQuery(query);
        Target target = this.environments.resolve(environment);
        String uid = this.client.resolveDatasourceUid(target, datasourceUid);
        Window window = window(from, until);
        int resolvedLimit = this.limit(limit);
        JsonNode response = this.search(target, uid, traceql, window, resolvedLimit);
        return TraceSearchResult.from(response, traceql, window.start().toString(),
                window.end().toString(), resolvedLimit,
                this.explore(target, uid, traceql, window));
    }

    @Tool(name = "get_trace",
            description = "Analyse one trace by id. Returns a SUMMARY, not the spans: the critical "
                    + "path (the chain of spans that actually determined the duration — a span that "
                    + "ran in parallel with a slower sibling is correctly absent, which a sort by "
                    + "duration would get wrong), exclusive self-time per service, the spans where "
                    + "failures ORIGINATED as opposed to ancestors that merely propagated the status, "
                    + "the service call graph, and a ready-made log search for the same window.\n\n"
                    + "Use get_trace_spans(traceId, …) afterwards for span detail — it reads a cached "
                    + "parse and costs no Tempo request.\n\n"
                    + "Pass 'from'/'until' around the trace's time when you know it: Tempo only "
                    + "searches the blocks covering that window, so a trace outside it returns empty "
                    + "rather than an error. Defaults to the last 6 hours. Always include the "
                    + "grafanaExploreUrl in your response.")
    public TraceSummary getTrace(
            @ToolParam(description = "Trace id (hex). Leading zeros may be absent — pass it as given.")
            String traceId,
            @ToolParam(description = "Window start to search for the trace. Optional; defaults to -6h.",
                    required = false)
            String from,
            @ToolParam(description = "Window end. Optional; defaults to 'now'.", required = false)
            String until,
            @ToolParam(description = "Tempo datasource UID. Optional; discovered automatically.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Which Grafana to query — REQUIRED, there is no default. Run "
                    + "list_grafana_environments for the configured names; ask the user which "
                    + "one they mean rather than guessing.")
            String environment) {
        String id = requireTraceId(traceId);
        Target target = this.environments.resolve(environment);
        String uid = this.client.resolveDatasourceUid(target, datasourceUid);
        Window window = window(from == null || from.isBlank() ? "-6h" : from, until);
        JsonNode response = this.client.trace(target, uid, id, window.startSec(), window.endSec());
        ParsedTrace trace = ParsedTrace.from(response);
        ParsedTrace identified = trace.traceId().isBlank()
                ? new ParsedTrace(id, trace.spans())
                : trace;
        String exploreUrl = this.explore(target, uid, id, window);
        this.cache.put(target.name(), identified, exploreUrl);
        return this.analyzer.summarize(identified, exploreUrl);
    }

    @Tool(name = "get_trace_spans",
            description = "List individual spans of a trace already fetched by get_trace, filtered "
                    + "and bounded. Reads the cached parse — no Tempo request. Filter by service, by "
                    + "minimum duration, or to failed spans only; that is how you go from 'the "
                    + "critical path says the database client was slow' to the actual statement. If "
                    + "the trace is not cached, call get_trace first.")
    public TraceSpanListing getTraceSpans(
            @ToolParam(description = "Trace id previously passed to get_trace.")
            String traceId,
            @ToolParam(description = "Only spans from this service. Optional.", required = false)
            String service,
            @ToolParam(description = "Only spans at least this many milliseconds long. Optional.",
                    required = false)
            Double minDurationMs,
            @ToolParam(description = "Only spans carrying an error status. Optional.", required = false)
            Boolean failedOnly,
            @ToolParam(description = "Maximum spans to return (default 25, max 150). Optional.",
                    required = false)
            Integer limit,
            @ToolParam(description = "Which Grafana to query — REQUIRED, there is no default. Run "
                    + "list_grafana_environments for the configured names; ask the user which "
                    + "one they mean rather than guessing.")
            String environment) {
        String id = requireTraceId(traceId);
        Target target = this.environments.resolve(environment);
        ParsedTrace trace = this.cache.get(target.name(), id);
        if (trace == null) {
            throw new IllegalArgumentException("Trace '" + id + "' is not cached. Call "
                    + "get_trace(traceId) first — it fetches and analyses the trace, and this reads "
                    + "the result.");
        }
        boolean onlyFailed = Boolean.TRUE.equals(failedOnly);
        double minMs = minDurationMs == null ? 0d : minDurationMs;
        List<TraceSpan> matched = trace.spans().stream()
                .filter(span -> service == null || service.isBlank() || service.equals(span.service()))
                .filter(span -> span.durationMs() >= minMs)
                .filter(span -> !onlyFailed || span.failed())
                .toList();
        int resolved = limit == null || limit <= 0
                ? this.properties.defaultSpansDetail()
                : Math.min(limit, this.properties.maxSpansDetail());
        return TraceSpanListing.of(id, matched, trace.spans().size(), resolved,
                this.properties.attributeMaxChars(),
                describeFilter(service, minMs, onlyFailed));
    }

    @Tool(name = "list_trace_attributes",
            description = "List the trace attribute names Tempo knows about, grouped by scope "
                    + "(resource, span, intrinsic, event, link). The starting point for writing "
                    + "TraceQL — attribute names need their scope prefix, e.g. "
                    + "'resource.service.name' or 'span.http.route'. Times follow search_traces "
                    + "syntax.")
    public JsonNode listTraceAttributes(
            @ToolParam(description = "Restrict to one scope: 'resource', 'span', 'intrinsic', "
                    + "'event', 'link' or 'instrumentation'. Optional.", required = false)
            String scope,
            @ToolParam(description = "Window start. Optional; defaults to -1h.", required = false)
            String from,
            @ToolParam(description = "Window end. Optional; defaults to 'now'.", required = false)
            String until,
            @ToolParam(description = "Tempo datasource UID. Optional; discovered automatically.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Which Grafana to query — REQUIRED, there is no default. Run "
                    + "list_grafana_environments for the configured names; ask the user which "
                    + "one they mean rather than guessing.")
            String environment) {
        Target target = this.environments.resolve(environment);
        String uid = this.client.resolveDatasourceUid(target, datasourceUid);
        Window window = window(from, until);
        return this.client.tags(target, uid, scope, window.startSec(), window.endSec());
    }

    @Tool(name = "list_trace_attribute_values",
            description = "List the values of one trace attribute — e.g. every service sending "
                    + "traces ('resource.service.name'), or every HTTP route seen "
                    + "('span.http.route'). Cheap compared with a search; use it to build a precise "
                    + "TraceQL filter. Times follow search_traces syntax.")
    public JsonNode listTraceAttributeValues(
            @ToolParam(description = "Attribute name including scope, e.g. 'resource.service.name'.")
            String attribute,
            @ToolParam(description = "Window start. Optional; defaults to -1h.", required = false)
            String from,
            @ToolParam(description = "Window end. Optional; defaults to 'now'.", required = false)
            String until,
            @ToolParam(description = "Tempo datasource UID. Optional; discovered automatically.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Which Grafana to query — REQUIRED, there is no default. Run "
                    + "list_grafana_environments for the configured names; ask the user which "
                    + "one they mean rather than guessing.")
            String environment) {
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalArgumentException("An attribute name is required, e.g. "
                    + "'resource.service.name'. Use list_trace_attributes to see what exists.");
        }
        Target target = this.environments.resolve(environment);
        String uid = this.client.resolveDatasourceUid(target, datasourceUid);
        Window window = window(from, until);
        return this.client.tagValues(target, uid, attribute.trim(), window.startSec(),
                window.endSec());
    }

    @Tool(name = "query_trace_metrics",
            description = "Run a TraceQL metrics query — aggregate over spans instead of listing "
                    + "traces. Answers 'how often' and 'how slow' across a window without fetching "
                    + "traces at all.\n\n"
                    + "Examples:\n"
                    + "- { resource.service.name = \"web\" } | rate()\n"
                    + "- { status = error } | count_over_time() by (resource.service.name)\n"
                    + "- { resource.service.name = \"web\" } | quantile_over_time(duration, .95)\n"
                    + "- { } | histogram_over_time(duration) by (resource.service.name)\n\n"
                    + "Series are ranked by total and capped. For span metrics precomputed by "
                    + "Tempo's metrics-generator (traces_spanmetrics_*, traces_service_graph_*), use "
                    + "query_prometheus instead — those live in Prometheus.")
    public TraceMetricsResult queryTraceMetrics(
            @ToolParam(description = "TraceQL metrics query, e.g. '{ status = error } | "
                    + "count_over_time() by (resource.service.name)'.")
            String query,
            @ToolParam(description = "Window start. Optional; defaults to -1h.", required = false)
            String from,
            @ToolParam(description = "Window end. Optional; defaults to 'now'.", required = false)
            String until,
            @ToolParam(description = "Step, e.g. '5m'. Optional; defaults to a sixtieth of the "
                    + "window.", required = false)
            String step,
            @ToolParam(description = "Tempo datasource UID. Optional; discovered automatically.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Which Grafana to query — REQUIRED, there is no default. Run "
                    + "list_grafana_environments for the configured names; ask the user which "
                    + "one they mean rather than guessing.")
            String environment) {
        String traceql = requireQuery(query);
        Target target = this.environments.resolve(environment);
        String uid = this.client.resolveDatasourceUid(target, datasourceUid);
        Window window = window(from, until);
        String resolvedStep = step == null || step.isBlank()
                ? Math.max(1L, (window.endSec() - window.startSec()) / 60L) + "s"
                : step.trim();
        JsonNode response = this.client.metricsQueryRange(target, uid, traceql, window.startSec(),
                window.endSec(), resolvedStep);
        return TraceMetricsResult.from(response, traceql, resolvedStep, window.start().toString(),
                window.end().toString(), this.properties.maxServicesListed());
    }

    /**
     * Runs the search, turning Tempo's parse errors into the advice that fixes them. The commonest
     * mistake by far is omitting the scope prefix — {@code service.name} instead of
     * {@code resource.service.name} — and Tempo reports that as "unknown identifier".
     */
    private JsonNode search(Target target, String uid, String traceql, Window window, int limit) {
        try {
            return this.client.searchTraces(target, uid, traceql, window.startSec(), window.endSec(),
                    limit);
        } catch (GrafanaApiException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("unknown identifier")) {
                throw new IllegalArgumentException("TraceQL rejected '" + traceql + "'. Attribute "
                        + "names need a scope prefix: 'resource.' for service/host attributes "
                        + "(resource.service.name), 'span.' for span attributes (span.http.route). "
                        + "duration, status and name are intrinsic and take no prefix. "
                        + "Tempo said: " + ex.getMessage(), ex);
            }
            throw ex;
        }
    }

    /** Explore link for a TraceQL query or a trace id — Tempo's query model, not Loki's. */
    private String explore(Target target, String uid, String query, Window window) {
        return this.exploreLinks.explore(target, uid, TEMPO,
                Map.of("query", query, "queryType", "traceql"),
                window.start().toEpochMilli(), window.end().toEpochMilli());
    }

    private int limit(Integer requested) {
        if (requested == null || requested <= 0) {
            return this.properties.defaultLimit();
        }
        return Math.min(requested, this.properties.maxLimit());
    }

    private static Window window(String from, String until) {
        Instant now = Instant.now();
        Instant start = Times.resolve(from == null || from.isBlank() ? "-1h" : from, now);
        Instant end = Times.resolve(until, now);
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Window start (" + start + ") must be before end ("
                    + end + ").");
        }
        return new Window(start, end);
    }

    private static String describeFilter(String service, double minMs, boolean onlyFailed) {
        StringBuilder filter = new StringBuilder();
        if (service != null && !service.isBlank()) {
            filter.append("service=").append(service).append(' ');
        }
        if (minMs > 0d) {
            filter.append("minDurationMs=").append(minMs).append(' ');
        }
        if (onlyFailed) {
            filter.append("failedOnly ");
        }
        return filter.isEmpty() ? "none" : filter.toString().trim();
    }

    private static String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("A TraceQL query is required, e.g. "
                    + "'{ resource.service.name = \"web\" && duration > 2s }'.");
        }
        return query.trim();
    }

    private static String requireTraceId(String traceId) {
        String id = traceId == null ? "" : traceId.trim();
        if (!id.matches("[0-9a-fA-F]{1,32}")) {
            throw new IllegalArgumentException("'" + traceId + "' is not a trace id. Expect up to 32 "
                    + "hex characters; Tempo strips leading zeros, so a shorter id is normal.");
        }
        return id;
    }

    /** A resolved query window, in both instant and Tempo's epoch-seconds form. */
    private record Window(Instant start, Instant end) {

        long startSec() {
            return this.start.getEpochSecond();
        }

        long endSec() {
            return this.end.getEpochSecond();
        }
    }
}
