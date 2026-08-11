package com.sparrowlogic.grafanamcp.domain;

import java.util.List;
import java.util.Map;

/**
 * What {@code get_trace} answers with: an analysis of a trace rather than the trace itself.
 *
 * <p>A production trace is hundreds to thousands of spans; returning it would bury the context
 * window and still leave the reader to work out where the time went. These fields are the answers
 * to the questions someone actually opens a trace to ask — what was slow, what failed, which
 * service owns the time, and where the matching logs are.
 *
 * @param traceId           the trace id
 * @param rootService       service that emitted the root span
 * @param rootName          root span name, e.g. {@code POST /api/orders}
 * @param durationMs        wall-clock duration of the whole trace
 * @param startTime         trace start (ISO-8601)
 * @param endTime           trace end (ISO-8601)
 * @param spanCount         total spans in the trace
 * @param serviceCount      distinct services involved
 * @param errorCount        spans carrying an error status, inherited ones included
 * @param criticalPath      the chain of spans that actually determined the duration, in time
 *                          order — a span that ran in parallel with a slower sibling is absent,
 *                          which is exactly what a sort by duration would get wrong
 * @param serviceSelfTime   time per service, most critical-path time first
 * @param errorOrigins      spans where a failure started, before ancestors propagated it
 * @param serviceCalls      service-to-service edges observed in this trace
 * @param logSearch         a ready-made log search for this trace, for search_loki_logs
 * @param grafanaExploreUrl link that opens this trace in Grafana Explore
 * @param notice            what was bounded or is worth knowing, or null
 */
public record TraceSummary(
        String traceId,
        String rootService,
        String rootName,
        double durationMs,
        String startTime,
        String endTime,
        int spanCount,
        int serviceCount,
        int errorCount,
        List<CriticalPathStep> criticalPath,
        List<ServiceTime> serviceSelfTime,
        List<ErrorSpan> errorOrigins,
        List<CallEdge> serviceCalls,
        LogSearchHint logSearch,
        String grafanaExploreUrl,
        String notice) {

    /**
     * One span's contribution to the trace's duration: the time during which nothing else was
     * blocking it.
     *
     * @param spanId          span id — pass to get_trace_spans for its attributes
     * @param service         service that emitted it
     * @param name            span name
     * @param kind            span kind, e.g. {@code SPAN_KIND_CLIENT}
     * @param msOnPath        milliseconds this span spent on the critical path
     * @param percentOfTrace  that time as a percentage of the trace's duration
     * @param failed          whether this span carries an error status
     */
    public record CriticalPathStep(String spanId, String service, String name, String kind,
            double msOnPath, double percentOfTrace, boolean failed) {
    }

    /**
     * Time attributed to one service, two ways.
     *
     * <p>{@code criticalPathMs} is the answer to "who owns the latency": it sums to the trace's
     * duration exactly, because the critical path is a partition of it. {@code selfMs} is each
     * span's duration minus its children's, summed — useful for spotting a service doing a lot of
     * work, but <em>not</em> additive: instrumentation that records concurrent operations as
     * overlapping siblings (Express middleware is the usual culprit) can push the total well past
     * the trace's duration. The percentage therefore comes from the critical path.
     *
     * @param service        the service
     * @param criticalPathMs milliseconds this service spent on the critical path
     * @param percentOfTrace {@code criticalPathMs} as a share of the trace's duration
     * @param selfMs         summed exclusive time — indicative, not additive
     * @param spanCount      spans this service contributed
     * @param errorCount     how many of those carry an error status
     */
    public record ServiceTime(String service, double criticalPathMs, double percentOfTrace,
            double selfMs, int spanCount, int errorCount) {
    }

    /**
     * A span where a failure originated — it failed and none of its children did.
     *
     * @param spanId        span id
     * @param service       service that emitted it
     * @param name          span name
     * @param statusMessage OTLP status message, when the instrumentation set one
     * @param detail        the attributes most likely to explain it (exception type/message, HTTP
     *                      status, DB statement), when present
     */
    public record ErrorSpan(String spanId, String service, String name, String statusMessage,
            Map<String, String> detail) {
    }

    /**
     * A service-to-service call observed in this trace.
     *
     * @param from   calling service
     * @param to     called service
     * @param calls  how many times
     * @param errors how many of those failed
     */
    public record CallEdge(String from, String to, int calls, int errors) {
    }

    /**
     * A log search that lines up with this trace, ready for {@code search_loki_logs}.
     *
     * <p>The window is padded because log ingestion lags span timestamps — an unpadded trace window
     * reliably returns nothing and reads as "there are no logs". The queries are built from the
     * span attributes that double as log stream labels in this deployment.
     *
     * @param from            padded window start (ISO-8601), for search_loki_logs
     * @param until           padded window end (ISO-8601)
     * @param suggestedQueries LogQL queries to try, most specific first
     * @param spanLabels      the span attributes the queries were derived from
     * @param note            what to expect, including whether trace-id correlation can work
     */
    public record LogSearchHint(String from, String until, List<String> suggestedQueries,
            Map<String, String> spanLabels, String note) {
    }
}
