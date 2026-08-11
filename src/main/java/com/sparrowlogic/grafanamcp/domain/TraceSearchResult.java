package com.sparrowlogic.grafanamcp.domain;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Hits from a TraceQL search, reduced to what is needed to choose which trace to open. Whole traces
 * are never inlined here — that is {@code get_trace}'s job, and it summarises rather than dumping.
 *
 * @param query             the TraceQL that was executed
 * @param fromResolved      resolved window start (ISO-8601)
 * @param untilResolved     resolved window end (ISO-8601)
 * @param returned          hits in this response
 * @param limitReached      true when Tempo returned exactly the requested limit, so older matching
 *                          traces may exist outside this page
 * @param traces            the hits, newest first as Tempo orders them
 * @param grafanaExploreUrl link that opens this search in Grafana Explore
 * @param notice            what to do next, or null
 */
public record TraceSearchResult(
        String query,
        String fromResolved,
        String untilResolved,
        int returned,
        boolean limitReached,
        List<TraceHit> traces,
        String grafanaExploreUrl,
        String notice) {

    /** Projects Tempo's {@code /api/search} response. */
    public static TraceSearchResult from(JsonNode root, String query, String from, String until,
            int limit, String exploreUrl) {
        List<TraceHit> hits = new ArrayList<>();
        JsonNode traces = root == null ? null : root.path("traces");
        if (traces != null && traces.isArray()) {
            traces.forEach(trace -> hits.add(hit(trace)));
        }
        boolean limitReached = hits.size() >= limit;
        return new TraceSearchResult(query, from, until, hits.size(), limitReached, List.copyOf(hits),
                exploreUrl, notice(hits.isEmpty(), limitReached, limit));
    }

    private static TraceHit hit(JsonNode trace) {
        List<String> matched = new ArrayList<>();
        trace.path("spanSet").path("spans").forEach(span -> matched.add(span.path("name").asString("")));
        if (matched.isEmpty()) {
            trace.path("spanSets").forEach(set -> set.path("spans")
                    .forEach(span -> matched.add(span.path("name").asString(""))));
        }
        long startNs = parseLong(trace.path("startTimeUnixNano").asString("0"));
        return new TraceHit(
                trace.path("traceID").asString(""),
                trace.path("rootServiceName").asString(""),
                trace.path("rootTraceName").asString(""),
                startNs == 0L ? null : com.sparrowlogic.grafanamcp.client.Times.fromNanos(startNs).toString(),
                trace.path("durationMs").asDouble(),
                trace.path("spanSet").path("matched").asInt(matched.size()),
                List.copyOf(matched));
    }

    private static String notice(boolean empty, boolean limitReached, int limit) {
        if (empty) {
            // A malformed attribute name is rejected by Tempo before this point, so an empty result
            // means the filter was valid and simply matched nothing.
            return "The query was valid but matched no traces. Widen the window, loosen the filter, "
                    + "or check the attribute's values with list_trace_attribute_values — sampling "
                    + "also means not every request is traced.";
        }
        return limitReached
                ? "Returned the full limit of " + limit + " traces, so more may match — narrow the "
                        + "window or tighten the TraceQL filter to be sure you are seeing the worst."
                : null;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * One matching trace.
     *
     * @param traceId      trace id — pass to get_trace
     * @param rootService  service that emitted the root span
     * @param rootName     root span name
     * @param startTime    trace start (ISO-8601)
     * @param durationMs   trace duration
     * @param matchedSpans how many spans in the trace matched the query
     * @param matchedSpanNames names of the matching spans Tempo returned (bounded by {@code spss})
     */
    public record TraceHit(String traceId, String rootService, String rootName, String startTime,
            double durationMs, int matchedSpans, List<String> matchedSpanNames) {
    }
}
