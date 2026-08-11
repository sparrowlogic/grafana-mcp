package com.sparrowlogic.grafanamcp.domain;

import java.util.Map;

/**
 * One span of a trace, flattened out of Tempo's response.
 *
 * <p>Timestamps are kept in nanoseconds because span analysis is interval arithmetic — overlap,
 * containment, gaps — and doing that in floating-point milliseconds loses the ordering of spans
 * that start within the same microsecond.
 *
 * @param spanId        span id (hex)
 * @param parentSpanId  parent span id, or null on the root
 * @param name          span name, e.g. {@code POST /api/orders}
 * @param service       {@code service.name} of the resource that emitted the span
 * @param scope         instrumentation scope that produced it, e.g. an OpenTelemetry library
 * @param kind          {@code SPAN_KIND_SERVER}, {@code SPAN_KIND_CLIENT}, …
 * @param startNs       span start, nanoseconds since the epoch
 * @param endNs         span end, nanoseconds since the epoch
 * @param statusCode    OTLP status, e.g. {@code STATUS_CODE_ERROR} ({@code STATUS_CODE_UNSET} is
 *                      the common case and does <em>not</em> mean failure)
 * @param statusMessage status message, usually empty unless the span failed
 * @param attributes    span attributes, already flattened to plain values
 */
public record TraceSpan(
        String spanId,
        String parentSpanId,
        String name,
        String service,
        String scope,
        String kind,
        long startNs,
        long endNs,
        String statusCode,
        String statusMessage,
        Map<String, String> attributes) {

    private static final double NANOS_PER_MILLI = 1_000_000d;

    /** True when this span carries an error status of its own. */
    public boolean failed() {
        return this.statusCode != null && this.statusCode.endsWith("ERROR");
    }

    /** Wall-clock duration in nanoseconds, never negative. */
    public long durationNs() {
        return Math.max(0L, this.endNs - this.startNs);
    }

    /** Duration in milliseconds, rounded for display. */
    public double durationMs() {
        return Math.round(this.durationNs() / NANOS_PER_MILLI * 1000d) / 1000d;
    }

    /** Milliseconds for an arbitrary nanosecond span, rounded the same way. */
    public static double toMillis(long nanos) {
        return Math.round(nanos / NANOS_PER_MILLI * 1000d) / 1000d;
    }
}
