package com.sparrowlogic.grafanamcp.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * A trace parsed out of Tempo's response into a flat, ordered span list plus parent/child links.
 *
 * <p>Parses Tempo's <b>LLM response format</b> ({@code Accept: application/vnd.grafana.llm}), which
 * nests spans as {@code trace.services[].scopes[].spans[]} with attributes already flattened to
 * plain values and the service name carried by the enclosing service. The OTLP form of the same
 * trace says the same thing in {@code resourceSpans[].scopeSpans[].spans[]} with every attribute
 * wrapped in a {@code {"key":…,"value":{"stringValue":…}}} envelope; both are handled, because the
 * LLM format is a Tempo 2.x convenience that a future version could withdraw.
 *
 * @param traceId the trace id as Tempo reports it
 * @param spans   every span, ordered by start time
 */
public record ParsedTrace(String traceId, List<TraceSpan> spans) {

    /** Parses either response format, detecting which one it was handed. */
    public static ParsedTrace from(JsonNode root) {
        JsonNode trace = root == null ? null : root.path("trace");
        JsonNode source = trace != null && !trace.isMissingNode() && !trace.isNull() ? trace : root;
        if (source == null) {
            return new ParsedTrace("", List.of());
        }
        List<TraceSpan> spans = source.path("services").isArray()
                ? llmSpans(source.path("services"))
                : otlpSpans(source.path("resourceSpans"));
        spans.sort(Comparator.comparingLong(TraceSpan::startNs));
        return new ParsedTrace(source.path("traceId").asString(""), List.copyOf(spans));
    }

    /** The span that has no parent (or the earliest span, if the trace is missing its root). */
    public TraceSpan root() {
        return this.spans.stream()
                .filter(span -> span.parentSpanId() == null || span.parentSpanId().isBlank())
                .findFirst()
                .orElseGet(() -> this.spans.isEmpty() ? null : this.spans.getFirst());
    }

    /** Children of each span id, in start order — the shape the analysis walks. */
    public Map<String, List<TraceSpan>> childrenBySpanId() {
        Map<String, List<TraceSpan>> children = new LinkedHashMap<>();
        for (TraceSpan span : this.spans) {
            String parent = span.parentSpanId();
            if (parent != null && !parent.isBlank()) {
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(span);
            }
        }
        return children;
    }

    /** Span by id, for drill-down. */
    public TraceSpan span(String spanId) {
        return this.spans.stream()
                .filter(span -> span.spanId().equals(spanId))
                .findFirst()
                .orElse(null);
    }

    /** Earliest span start in the trace, or 0 when empty. */
    public long startNs() {
        return this.spans.stream().mapToLong(TraceSpan::startNs).min().orElse(0L);
    }

    /** Latest span end in the trace, or 0 when empty. */
    public long endNs() {
        return this.spans.stream().mapToLong(TraceSpan::endNs).max().orElse(0L);
    }

    private static List<TraceSpan> llmSpans(JsonNode services) {
        List<TraceSpan> spans = new ArrayList<>();
        for (JsonNode service : services) {
            llmServiceSpans(service, spans);
        }
        return spans;
    }

    private static void llmServiceSpans(JsonNode service, List<TraceSpan> spans) {
        String serviceName = service.path("serviceName").asString("");
        for (JsonNode scope : service.path("scopes")) {
            String scopeName = scope.path("name").asString("");
            scope.path("spans").forEach(span -> spans.add(llmSpan(span, serviceName, scopeName)));
        }
    }

    private static TraceSpan llmSpan(JsonNode span, String service, String scope) {
        Map<String, String> attributes = new LinkedHashMap<>();
        span.path("attributes").propertyStream()
                .forEach(field -> attributes.put(field.getKey(), field.getValue().asString()));
        return new TraceSpan(
                span.path("spanId").asString(""),
                emptyToNull(span.path("parentSpanId").asString("")),
                span.path("name").asString(""),
                service, scope,
                span.path("kind").asString(""),
                asNanos(span.path("startTimeUnixNano")),
                asNanos(span.path("endTimeUnixNano")),
                span.path("status").path("code").asString(""),
                span.path("status").path("message").asString(""),
                Map.copyOf(attributes));
    }

    private static List<TraceSpan> otlpSpans(JsonNode resourceSpans) {
        List<TraceSpan> spans = new ArrayList<>();
        for (JsonNode resourceSpan : resourceSpans) {
            otlpResourceSpans(resourceSpan, spans);
        }
        return spans;
    }

    private static void otlpResourceSpans(JsonNode resourceSpan, List<TraceSpan> spans) {
        Map<String, String> resource = otlpAttributes(resourceSpan.path("resource").path("attributes"));
        String service = resource.getOrDefault("service.name", "");
        for (JsonNode scopeSpan : resourceSpan.path("scopeSpans")) {
            String scope = scopeSpan.path("scope").path("name").asString("");
            scopeSpan.path("spans").forEach(span -> spans.add(otlpSpan(span, service, scope)));
        }
    }

    private static TraceSpan otlpSpan(JsonNode span, String service, String scope) {
        return new TraceSpan(
                span.path("spanId").asString(""),
                emptyToNull(span.path("parentSpanId").asString("")),
                span.path("name").asString(""),
                service, scope,
                span.path("kind").asString(""),
                asNanos(span.path("startTimeUnixNano")),
                asNanos(span.path("endTimeUnixNano")),
                span.path("status").path("code").asString(""),
                span.path("status").path("message").asString(""),
                otlpAttributes(span.path("attributes")));
    }

    /** Unwraps OTLP's {@code {"key":…,"value":{"stringValue":…}}} attribute envelopes. */
    private static Map<String, String> otlpAttributes(JsonNode attributes) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (JsonNode attribute : attributes) {
            JsonNode value = attribute.path("value");
            String first = value.propertyStream().findFirst()
                    .map(field -> field.getValue().asString())
                    .orElse("");
            flat.put(attribute.path("key").asString(""), first);
        }
        return Map.copyOf(flat);
    }

    /** Tempo sends nanosecond timestamps as strings; a number is accepted too. */
    private static long asNanos(JsonNode node) {
        return node.isNumber() ? node.asLong() : parseLong(node.asString("0"));
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
