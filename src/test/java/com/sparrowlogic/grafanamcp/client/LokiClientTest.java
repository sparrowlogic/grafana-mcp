package com.sparrowlogic.grafanamcp.client;

import com.sparrowlogic.grafanamcp.config.LokiProperties;
import com.sparrowlogic.grafanamcp.domain.FetchedLogs;
import com.sparrowlogic.grafanamcp.domain.LogEntry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LokiClientTest {

    private static final String UID = "loki-uid";
    private static final String QUERY = "{app=\"a\"}";
    private static final String PROXY =
            "https://grafana.test/api/datasources/proxy/uid/loki-uid/loki/api/v1";

    private MockRestServiceServer mockServer;
    private LokiClient client;
    private GrafanaEnvironments.Target target;

    /** Builds a client whose fetch cap and per-slice limit are small enough to exercise the walk. */
    private LokiClient client(int maxFetch, int pageLimit) {
        RestClient.Builder builder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(builder).build();
        LokiProperties properties = new LokiProperties(null, maxFetch, pageLimit, 25, 200, 20000, 120, 2000,
                5, 30, 2, 25);
        this.target = target(builder, "https://grafana.test");
        this.client = new LokiClient(new DatasourceProxy(), properties);
        return this.client;
    }

    /** An environment whose client is bound to the mock server. */
    private static GrafanaEnvironments.Target target(RestClient.Builder builder, String baseUrl) {
        return new GrafanaEnvironments.Target("test", baseUrl, builder.build());
    }

    /** A streams response body; each stream is a {@code labels -> [[ts, line], …]} pair. */
    private static String body(String... streams) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":["
                + String.join(",", streams)
                + "],\"stats\":{\"summary\":{\"execTime\":0.01,\"totalLinesProcessed\":42}}}}";
    }

    private static String stream(String labels, String... values) {
        return "{\"stream\":" + labels + ",\"values\":[" + String.join(",", values) + "]}";
    }

    private static String value(long timestampNs, String line) {
        return "[\"" + timestampNs + "\",\"" + line + "\"]";
    }

    @Test
    void logQlBracesAreEncodedNotTreatedAsUriTemplate() {
        this.client(10, 5);
        this.mockServer.expect(requestTo(PROXY + "/query_range"
                        + "?query=%7Bapp%3D%22a%22%7D&start=1000&end=2000&limit=5&direction=backward"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));

        this.client.fetchEntries(this.target, UID, QUERY, 1000L, 2000L, true);
        this.mockServer.verify();
    }

    @Test
    void plusInARegexIsEncodedSoLokiDoesNotReadItAsASpace() {
        this.client(10, 5);
        // Regression: Go decodes a literal '+' in a query value as a space, so {env=~".+"} would
        // reach Loki as {env=~". "} and match nothing — success with zero results, no error.
        this.mockServer.expect(requestTo(containsString("query=%7Benvironment%3D~%22.%2B%22%7D")))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));

        this.client.fetchEntries(this.target, UID, "{environment=~\".+\"}", 1000L, 2000L, true);
        this.mockServer.verify();
    }

    @Test
    void spacesAndBackticksInAFilterAreEncoded() {
        this.client(10, 5);
        this.mockServer.expect(requestTo(containsString("%7D%20%7C%3D%20%60abc%60")))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));

        this.client.fetchEntries(this.target, UID, "{app=\"a\"} |= `abc`", 1000L, 2000L, true);
        this.mockServer.verify();
    }

    @Test
    void mergesStreamsNewestFirstAndFoldsRepeatedLabelSets() {
        this.client(10, 5);
        this.mockServer.expect(requestTo(containsString("/query_range")))
                .andRespond(withSuccess(body(
                        stream("{\"app\":\"a\"}", value(1700L, "oldest"), value(1900L, "newest")),
                        stream("{\"app\":\"b\"}", value(1800L, "middle"))),
                        MediaType.APPLICATION_JSON));

        FetchedLogs fetched = this.client.fetchEntries(this.target, UID, QUERY, 1000L, 2000L, true);

        assertThat(fetched.entries()).extracting(LogEntry::line)
                .containsExactly("newest", "middle", "oldest");
        assertThat(fetched.entries()).extracting(LogEntry::id).containsExactly("e0", "e1", "e2");
        assertThat(fetched.streams()).containsExactly(java.util.Map.of("app", "a"),
                java.util.Map.of("app", "b"));
        assertThat(fetched.entries()).extracting(LogEntry::streamIndex).containsExactly(0, 1, 0);
        assertThat(fetched.capped()).isFalse();
    }

    @Test
    void walksBackwardAcrossSlicesAndDropsTheOverlap() {
        this.client(10, 2);
        // Slice 1: a full slice, so the walk continues from one nanosecond past the oldest entry.
        this.mockServer.expect(requestTo(containsString("end=2000")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1800L, "b"), value(1900L, "a"))), MediaType.APPLICATION_JSON));
        // Slice 2 re-reads ts 1800 (the boundary nanosecond stays in the window) — de-duped.
        this.mockServer.expect(requestTo(containsString("end=1801")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1700L, "c"), value(1800L, "b"))), MediaType.APPLICATION_JSON));
        // Slice 3 comes back short: the window is covered.
        this.mockServer.expect(requestTo(containsString("end=1701")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1700L, "c"))), MediaType.APPLICATION_JSON));

        FetchedLogs fetched = this.client.fetchEntries(this.target, UID, QUERY, 1000L, 2000L, true);

        assertThat(fetched.entries()).extracting(LogEntry::line).containsExactly("a", "b", "c");
        assertThat(fetched.capped()).isFalse();
        this.mockServer.verify();
    }

    @Test
    void walksForwardFromTheBoundaryTimestamp() {
        this.client(10, 2);
        this.mockServer.expect(requestTo(containsString("start=1000")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1100L, "a"), value(1200L, "b"))), MediaType.APPLICATION_JSON));
        // Forward walking resumes AT the newest timestamp (start is inclusive), not past it.
        this.mockServer.expect(requestTo(containsString("start=1200")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1200L, "b"), value(1300L, "c"))), MediaType.APPLICATION_JSON));
        this.mockServer.expect(requestTo(containsString("start=1300")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1300L, "c"))), MediaType.APPLICATION_JSON));

        FetchedLogs fetched = this.client.fetchEntries(this.target, UID, QUERY, 1000L, 2000L, false);

        assertThat(fetched.entries()).extracting(LogEntry::line).containsExactly("a", "b", "c");
        assertThat(fetched.capped()).isFalse();
        this.mockServer.verify();
    }

    @Test
    void stopsAtTheFetchCapAndReportsCapped() {
        this.client(3, 2);
        this.mockServer.expect(requestTo(containsString("end=2000")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1800L, "b"), value(1900L, "a"))), MediaType.APPLICATION_JSON));
        this.mockServer.expect(requestTo(containsString("end=1801")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1600L, "d"), value(1700L, "c"))), MediaType.APPLICATION_JSON));

        FetchedLogs fetched = this.client.fetchEntries(this.target, UID, QUERY, 1000L, 2000L, true);

        assertThat(fetched.entries()).extracting(LogEntry::line).containsExactly("a", "b", "c");
        assertThat(fetched.capped()).isTrue();
        this.mockServer.verify();
    }

    @Test
    void reportsCappedWhenASingleNanosecondFillsASlice() {
        this.client(10, 2);
        // Every entry shares a timestamp, so the cursor cannot move: stop rather than loop, and
        // admit the result is incomplete.
        this.mockServer.expect(requestTo(containsString("end=2000")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1500L, "a"), value(1500L, "b"))), MediaType.APPLICATION_JSON));
        this.mockServer.expect(requestTo(containsString("end=1501")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1500L, "a"), value(1500L, "b"))), MediaType.APPLICATION_JSON));

        FetchedLogs fetched = this.client.fetchEntries(this.target, UID, QUERY, 1000L, 2000L, true);

        assertThat(fetched.entries()).hasSize(2);
        assertThat(fetched.capped()).isTrue();
        this.mockServer.verify();
    }

    @Test
    void stopsWhenTheWalkReachesTheWindowStart() {
        this.client(10, 2);
        // A full slice reaching the window start still leaves the possibility of more entries AT
        // that nanosecond, so one bounded slice is issued for it before declaring coverage.
        this.mockServer.expect(requestTo(containsString("end=2000")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1000L, "oldest"), value(1900L, "newest"))), MediaType.APPLICATION_JSON));
        this.mockServer.expect(requestTo(containsString("start=1000&end=1001")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(1000L, "oldest"))), MediaType.APPLICATION_JSON));

        FetchedLogs fetched = this.client.fetchEntries(this.target, UID, QUERY, 1000L, 2000L, true);

        assertThat(fetched.entries()).extracting(LogEntry::line).containsExactly("newest", "oldest");
        assertThat(fetched.capped()).isFalse();
        this.mockServer.verify();
    }

    @Test
    void stopsWithoutAnExtraCallOnceTheCursorLeavesTheWindow() {
        this.client(10, 2);
        // The oldest entry sits below the window start (Loki's start is inclusive, so this only
        // happens with an off-boundary slice), leaving nothing addressable to ask for.
        this.mockServer.expect(requestTo(containsString("end=2000")))
                .andRespond(withSuccess(body(stream("{\"app\":\"a\"}",
                        value(999L, "before"), value(1900L, "newest"))), MediaType.APPLICATION_JSON));

        FetchedLogs fetched = this.client.fetchEntries(this.target, UID, QUERY, 1000L, 2000L, true);

        assertThat(fetched.entries()).hasSize(2);
        assertThat(fetched.capped()).isFalse();
        this.mockServer.verify();
    }

    @Test
    void emptyResultIsNotCapped() {
        this.client(10, 2);
        this.mockServer.expect(requestTo(containsString("/query_range")))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));

        FetchedLogs fetched = this.client.fetchEntries(this.target, UID, QUERY, 1000L, 2000L, true);

        assertThat(fetched.entries()).isEmpty();
        assertThat(fetched.streams()).isEmpty();
        assertThat(fetched.capped()).isFalse();
    }

    @Test
    void labelsAreReadFromTheDataArray() {
        this.client(10, 5);
        this.mockServer.expect(requestTo(PROXY + "/labels?start=1000&end=2000"))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":[\"level\",\"service\"]}",
                        MediaType.APPLICATION_JSON));

        assertThat(this.client.labels(this.target, UID, 1000L, 2000L)).containsExactly("level", "service");
        this.mockServer.verify();
    }

    @Test
    void labelValuesPassTheSelectorOnlyWhenGiven() {
        this.client(10, 5);
        this.mockServer.expect(requestTo(PROXY + "/label/level/values?start=1000&end=2000"))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":[\"ERROR\"]}",
                        MediaType.APPLICATION_JSON));
        this.mockServer.expect(requestTo(PROXY + "/label/level/values"
                        + "?start=1000&end=2000&query=%7Bapp%3D%22a%22%7D"))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":[\"WARN\"]}",
                        MediaType.APPLICATION_JSON));

        assertThat(this.client.labelValues(this.target, UID, "level", 1000L, 2000L, " ")).containsExactly("ERROR");
        assertThat(this.client.labelValues(this.target, UID, "level", 1000L, 2000L, QUERY)).containsExactly("WARN");
        this.mockServer.verify();
    }

    @Test
    void metricQueryPassesStepAndReturnsTheMatrixVerbatim() {
        this.client(10, 5);
        this.mockServer.expect(requestTo(containsString("step=300s")))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\","
                        + "\"result\":[{\"metric\":{},\"values\":[[1786381500,\"5\"]]}]}}",
                        MediaType.APPLICATION_JSON));

        JsonNode matrix = this.client.metricQuery(this.target, UID, "sum(count_over_time({app=\"a\"}[300s]))",
                1000L, 2000L, 300L);

        assertThat(matrix.path("data").path("resultType").asString()).isEqualTo("matrix");
        this.mockServer.verify();
    }

    @Test
    void httpErrorsSurfaceAsGrafanaApiException() {
        this.client(10, 5);
        this.mockServer.expect(requestTo(containsString("/query_range")))
                .andRespond(withServerError().body("parse error"));

        assertThatThrownBy(() -> this.client.fetchEntries(this.target, UID, QUERY, 1000L, 2000L, true))
                .isInstanceOf(GrafanaApiException.class)
                .hasMessageContaining("parse error");
    }

    @Test
    void explicitUidWinsWithoutQueryingGrafana() {
        this.client(10, 5);

        assertThat(this.client.resolveDatasourceUid(this.target, "explicit-uid")).isEqualTo("explicit-uid");
        // No /api/datasources lookup was needed.
        this.mockServer.verify();
    }

    @Test
    void configuredUidIsUsedWhenNoneIsPassed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LokiClient configured = new LokiClient(new DatasourceProxy(),
                new LokiProperties("configured-uid", 10, 5, 25, 200, 20000, 120, 2000, 5, 30, 2, 25));

        assertThat(configured.resolveDatasourceUid(target(builder, "https://grafana.test"), null))
                .isEqualTo("configured-uid");
        server.verify();
    }

    @Test
    void lokiDatasourceIsDiscoveredOnceAndRemembered() {
        this.client(10, 5);
        this.mockServer.expect(requestTo("https://grafana.test/api/datasources"))
                .andRespond(withSuccess("""
                        [{"id":1,"uid":"prom-uid","name":"Prometheus","type":"prometheus","isDefault":true},
                         {"id":2,"uid":"found-uid","name":"Loki","type":"loki","isDefault":false}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(this.client.resolveDatasourceUid(this.target, null)).isEqualTo("found-uid");
        // Second call is served from the cache: only the one expectation above is consumed.
        assertThat(this.client.resolveDatasourceUid(this.target, "  ")).isEqualTo("found-uid");
        this.mockServer.verify();
    }

    @Test
    void missingLokiDatasourceIsAnActionableFailure() {
        this.client(10, 5);
        this.mockServer.expect(requestTo("https://grafana.test/api/datasources"))
                .andRespond(withSuccess("""
                        [{"id":1,"uid":"prom-uid","name":"Prometheus","type":"prometheus","isDefault":true}]
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> this.client.resolveDatasourceUid(this.target, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No datasource of type 'loki'");
    }

    @Test
    void trailingSlashOnTheBaseUrlDoesNotDoubleUpThePath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // A base URL with a trailing slash must not produce a doubled path separator.
        LokiClient trailing = new LokiClient(new DatasourceProxy(),
                new LokiProperties(null, 10, 5, 25, 200, 20000, 120, 2000, 5, 30, 2, 25));
        GrafanaEnvironments.Target trailingTarget = new GrafanaEnvironments.Target("test",
                "https://grafana.test", builder.build());
        server.expect(requestTo(PROXY + "/labels?start=1000&end=2000"))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":[]}",
                        MediaType.APPLICATION_JSON));

        assertThat(trailing.labels(trailingTarget, UID, 1000L, 2000L)).isEmpty();
        server.verify();
    }
}
