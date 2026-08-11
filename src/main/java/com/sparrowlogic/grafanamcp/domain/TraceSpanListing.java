package com.sparrowlogic.grafanamcp.domain;

import com.sparrowlogic.grafanamcp.client.Times;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit span detail for a trace, read from the cached parse rather than re-fetched. The opt-in
 * counterpart to {@link TraceSummary}: use it after the summary has narrowed the question to a
 * service, a failure, or a stretch of the critical path.
 *
 * @param traceId   the trace these spans belong to
 * @param filter    a description of the filter that was applied, for the record
 * @param returned  spans in this response
 * @param matched   spans that matched the filter
 * @param total     spans in the whole trace
 * @param truncated true when {@code matched > returned}
 * @param spans     the spans, in start order
 * @param notice    how to narrow further, or null
 */
public record TraceSpanListing(
        String traceId,
        String filter,
        int returned,
        int matched,
        int total,
        boolean truncated,
        List<SpanView> spans,
        String notice) {

    /** Projects spans for display, truncating attribute values. */
    public static TraceSpanListing of(String traceId, List<TraceSpan> matched, int total, int limit,
            int attributeMaxChars, String filter) {
        List<SpanView> views = matched.stream()
                .limit(limit)
                .map(span -> view(span, attributeMaxChars))
                .toList();
        boolean truncated = matched.size() > views.size();
        return new TraceSpanListing(traceId, filter, views.size(), matched.size(), total, truncated,
                views, truncated
                        ? "Showing " + views.size() + " of " + matched.size() + " matching spans — "
                                + "filter by service, minDurationMs or failedOnly to narrow it."
                        : null);
    }

    private static SpanView view(TraceSpan span, int attributeMaxChars) {
        Map<String, String> attributes = new LinkedHashMap<>();
        span.attributes().forEach((key, value) -> attributes.put(key, truncate(value, attributeMaxChars)));
        return new SpanView(span.spanId(), span.parentSpanId(), span.service(), span.name(),
                span.kind(), Times.fromNanos(span.startNs()).toString(), span.durationMs(),
                span.statusCode(), span.statusMessage(), Map.copyOf(attributes));
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "…";
    }

    /**
     * One span as returned to a caller.
     *
     * @param spanId        span id
     * @param parentSpanId  parent span id, or null on the root
     * @param service       emitting service
     * @param name          span name
     * @param kind          span kind
     * @param startTime     span start (ISO-8601)
     * @param durationMs    span duration
     * @param statusCode    OTLP status code
     * @param statusMessage OTLP status message
     * @param attributes    span attributes, values truncated
     */
    public record SpanView(String spanId, String parentSpanId, String service, String name,
            String kind, String startTime, double durationMs, String statusCode, String statusMessage,
            Map<String, String> attributes) {
    }
}
