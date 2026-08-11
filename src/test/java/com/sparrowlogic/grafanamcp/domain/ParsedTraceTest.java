package com.sparrowlogic.grafanamcp.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedTraceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The shape Tempo 2.10 returns for {@code Accept: application/vnd.grafana.llm}. */
    private static final String LLM = """
            {"trace":{"traceId":"0af7651916cd43dd8448eb211c80319c","services":[
              {"serviceName":"checkout-api",
               "resource":{"deployment.environment.name":"production","host.ip":"10.0.0.15"},
               "scopes":[
                 {"name":"@opentelemetry/instrumentation-http","version":"0.69.0","spans":[
                   {"spanId":"b7ad6b7169203331","name":"POST /api/orders",
                    "kind":"SPAN_KIND_SERVER","startTimeUnixNano":"1786389004000000000",
                    "endTimeUnixNano":"1786389004253000000","durationMs":253.0,
                    "attributes":{"http.route":"/api/orders","service_name":"checkout-api",
                                  "http.response.status_code":201},
                    "status":{"code":"STATUS_CODE_UNSET","message":""}}]},
                 {"name":"@opentelemetry/instrumentation-pg","spans":[
                   {"spanId":"a2fb4a1d1a96d312","name":"pg.query","parentSpanId":"b7ad6b7169203331",
                    "kind":"SPAN_KIND_CLIENT","startTimeUnixNano":"1786389004050000000",
                    "endTimeUnixNano":"1786389004250000000","durationMs":200.0,
                    "attributes":{"db.query.text":"SELECT 1"},
                    "status":{"code":"STATUS_CODE_ERROR","message":"connection reset"}}]}]}]},
             "metrics":{"inspectedBytes":47517}}""";

    /** The same trace as OTLP, with attributes in type envelopes. */
    private static final String OTLP = """
            {"trace":{"resourceSpans":[
              {"resource":{"attributes":[
                 {"key":"service.name","value":{"stringValue":"checkout-api"}}]},
               "scopeSpans":[
                 {"scope":{"name":"@opentelemetry/instrumentation-http"},"spans":[
                   {"spanId":"b7ad6b7169203331","name":"POST /api/orders",
                    "kind":"SPAN_KIND_SERVER","startTimeUnixNano":"1786389004000000000",
                    "endTimeUnixNano":"1786389004253000000",
                    "attributes":[{"key":"http.route","value":{"stringValue":"/api/orders"}},
                                  {"key":"http.response.status_code","value":{"intValue":"201"}}],
                    "status":{"code":"STATUS_CODE_UNSET"}}]}]}]}}""";

    @Test
    void parsesTheLlmFormatIncludingServiceAndScope() {
        ParsedTrace trace = ParsedTrace.from(MAPPER.readTree(LLM));

        assertThat(trace.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(trace.spans()).hasSize(2);
        TraceSpan root = trace.root();
        assertThat(root.spanId()).isEqualTo("b7ad6b7169203331");
        assertThat(root.service()).isEqualTo("checkout-api");
        assertThat(root.scope()).isEqualTo("@opentelemetry/instrumentation-http");
        assertThat(root.parentSpanId()).isNull();
        assertThat(root.durationMs()).isEqualTo(253d);
        // Attribute values are already flat, and a numeric one is readable as a string.
        assertThat(root.attributes()).containsEntry("http.response.status_code", "201");
        assertThat(root.failed()).isFalse();
    }

    @Test
    void spansAreOrderedByStartAndLinkedToTheirParent() {
        ParsedTrace trace = ParsedTrace.from(MAPPER.readTree(LLM));

        assertThat(trace.spans()).extracting(TraceSpan::name)
                .containsExactly("POST /api/orders", "pg.query");
        assertThat(trace.childrenBySpanId().get("b7ad6b7169203331")).hasSize(1);
        assertThat(trace.span("a2fb4a1d1a96d312").parentSpanId()).isEqualTo("b7ad6b7169203331");
        assertThat(trace.startNs()).isEqualTo(1786389004000000000L);
        assertThat(trace.endNs()).isEqualTo(1786389004253000000L);
    }

    @Test
    void anErrorStatusIsRecognised() {
        ParsedTrace trace = ParsedTrace.from(MAPPER.readTree(LLM));

        TraceSpan query = trace.span("a2fb4a1d1a96d312");
        assertThat(query.failed()).isTrue();
        assertThat(query.statusMessage()).isEqualTo("connection reset");
    }

    @Test
    void parsesOtlpAsWellSoAFutureTempoCanDropTheLlmFormat() {
        ParsedTrace trace = ParsedTrace.from(MAPPER.readTree(OTLP));

        assertThat(trace.spans()).hasSize(1);
        TraceSpan root = trace.root();
        assertThat(root.service()).isEqualTo("checkout-api");
        // The {"intValue":"201"} envelope is unwrapped the same as a string one.
        assertThat(root.attributes()).containsEntry("http.response.status_code", "201");
        assertThat(root.durationMs()).isEqualTo(253d);
    }

    @Test
    void aMissingOrEmptyResponseParsesToAnEmptyTrace() {
        assertThat(ParsedTrace.from(null).spans()).isEmpty();
        assertThat(ParsedTrace.from(MAPPER.readTree("{}")).spans()).isEmpty();
        assertThat(ParsedTrace.from(MAPPER.readTree("{\"trace\":{\"services\":[]}}")).root()).isNull();
    }
}
