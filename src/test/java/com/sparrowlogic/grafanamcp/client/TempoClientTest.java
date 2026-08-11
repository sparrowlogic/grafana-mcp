package com.sparrowlogic.grafanamcp.client;

import com.sparrowlogic.grafanamcp.config.TempoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TempoClientTest {

    private static final String UID = "tempo";
    private static final String PROXY = "https://grafana.test/api/datasources/proxy/uid/tempo";

    private MockRestServiceServer mockServer;
    private TempoClient client;
    private GrafanaEnvironments.Target target;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(builder).build();
        this.target = new GrafanaEnvironments.Target("test", "https://grafana.test", builder.build());
        this.client = new TempoClient(new DatasourceProxy(),
                new TempoProperties(UID, 20, 100, 3, 25, 150, 15, 25, 200, 30, 30));
    }

    @Test
    void searchEncodesTraceQlAndPassesSpansPerHit() {
        this.mockServer.expect(requestTo(PROXY + "/api/search"
                        + "?q=%7B%20duration%20%3E%202s%20%7D&start=1000&end=2000&limit=20&spss=3"))
                .andRespond(withSuccess("{\"traces\":[]}", MediaType.APPLICATION_JSON));

        this.client.searchTraces(this.target, UID, "{ duration > 2s }", 1000L, 2000L, 20);
        this.mockServer.verify();
    }

    @Test
    void traceAsksForTheLlmFormatAndSendsTheWindow() {
        this.mockServer.expect(requestTo(PROXY + "/api/v2/traces/abc123?start=1000&end=2000"))
                .andExpect(header("Accept", "application/vnd.grafana.llm"))
                .andRespond(withSuccess("{\"trace\":{\"traceId\":\"abc123\",\"services\":[]}}",
                        MediaType.APPLICATION_JSON));

        JsonNode trace = this.client.trace(this.target, UID, "abc123", 1000L, 2000L);

        assertThat(trace.path("trace").path("traceId").asString()).isEqualTo("abc123");
        this.mockServer.verify();
    }

    @Test
    void aTempoThatRejectsTheLlmFormatFallsBackToOtlp() {
        this.mockServer.expect(requestTo(containsString("/api/v2/traces/abc123")))
                .andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE)
                        .body("unsupported"));
        this.mockServer.expect(requestTo(containsString("/api/v2/traces/abc123")))
                .andRespond(withSuccess("{\"trace\":{\"resourceSpans\":[]}}",
                        MediaType.APPLICATION_JSON));

        JsonNode trace = this.client.trace(this.target, UID, "abc123", 1000L, 2000L);

        assertThat(trace.path("trace").has("resourceSpans")).isTrue();
        this.mockServer.verify();
    }

    @Test
    void tagsUsesTheScopedV2Endpoint() {
        this.mockServer.expect(requestTo(PROXY + "/api/v2/search/tags?start=1000&end=2000&scope=resource"))
                .andRespond(withSuccess("{\"scopes\":[]}", MediaType.APPLICATION_JSON));

        this.client.tags(this.target, UID, "resource", 1000L, 2000L);
        this.mockServer.verify();
    }

    @Test
    void tagsFallsBackToV1WhenV2IsAbsent() {
        this.mockServer.expect(requestTo(containsString("/api/v2/search/tags")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no v2"));
        this.mockServer.expect(requestTo(PROXY + "/api/search/tags?start=1000&end=2000"))
                .andRespond(withSuccess("{\"tagNames\":[\"service.name\"]}", MediaType.APPLICATION_JSON));

        JsonNode fallback = this.client.tags(this.target, UID, null, 1000L, 2000L);

        assertThat(fallback.path("tagNames").path(0).asString()).isEqualTo("service.name");
        this.mockServer.verify();
    }

    @Test
    void tagValuesEncodesTheAttributeNameInThePath() {
        this.mockServer.expect(requestTo(
                        PROXY + "/api/v2/search/tag/resource.service.name/values?start=1000&end=2000"))
                .andRespond(withSuccess("{\"tagValues\":[]}", MediaType.APPLICATION_JSON));

        this.client.tagValues(this.target, UID, "resource.service.name", 1000L, 2000L);
        this.mockServer.verify();
    }

    @Test
    void metricsQueryPassesTheStep() {
        this.mockServer.expect(requestTo(containsString("&step=5m")))
                .andRespond(withSuccess("{\"series\":[]}", MediaType.APPLICATION_JSON));

        this.client.metricsQueryRange(this.target, UID, "{} | rate()", 1000L, 2000L, "5m");
        this.mockServer.verify();
    }

    @Test
    void datasourceResolutionPrefersTheConfiguredUid() {
        assertThat(this.client.resolveDatasourceUid(this.target, null)).isEqualTo(UID);
        assertThat(this.client.resolveDatasourceUid(this.target, "explicit")).isEqualTo("explicit");
        this.mockServer.verify();
    }
}
