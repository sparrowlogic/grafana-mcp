package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.analysis.TraceAnalyzer;
import com.sparrowlogic.grafanamcp.cache.TraceCacheService;
import com.sparrowlogic.grafanamcp.client.ExploreLinks;
import com.sparrowlogic.grafanamcp.client.GrafanaApiException;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.client.TempoClient;
import com.sparrowlogic.grafanamcp.config.TempoProperties;
import com.sparrowlogic.grafanamcp.domain.TraceMetricsResult;
import com.sparrowlogic.grafanamcp.domain.TraceSearchResult;
import com.sparrowlogic.grafanamcp.domain.TraceSpanListing;
import com.sparrowlogic.grafanamcp.domain.TraceSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TempoToolsTest {

    private static final String UID = "tempo";
    private static final GrafanaEnvironments.Target TARGET =
            new GrafanaEnvironments.Target("production", "https://grafana.test", null);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** A two-span trace in Tempo's LLM format: web calls a database client that fails. */
    private static final String TRACE = """
            {"trace":{"traceId":"abc123","services":[
              {"serviceName":"web","resource":{},"scopes":[{"name":"http","spans":[
                {"spanId":"s1","name":"POST /checkout","kind":"SPAN_KIND_SERVER",
                 "startTimeUnixNano":"1786389004000000000","endTimeUnixNano":"1786389004100000000",
                 "attributes":{"service_name":"web"},"status":{"code":"STATUS_CODE_UNSET"}}]}]},
              {"serviceName":"db","resource":{},"scopes":[{"name":"pg","spans":[
                {"spanId":"s2","parentSpanId":"s1","name":"INSERT orders","kind":"SPAN_KIND_CLIENT",
                 "startTimeUnixNano":"1786389004010000000","endTimeUnixNano":"1786389004090000000",
                 "attributes":{"db.query.text":"INSERT INTO orders VALUES (…)"},
                 "status":{"code":"STATUS_CODE_ERROR","message":"deadlock"}}]}]}]}}""";

    private final TempoClient client = mock(TempoClient.class);
    private final ExploreLinks exploreLinks = mock(ExploreLinks.class);
    private final GrafanaEnvironments environments = mock(GrafanaEnvironments.class);
    private final TempoProperties properties =
            new TempoProperties(null, 20, 100, 3, 25, 150, 15, 25, 200, 30, 30);

    private TempoTools tools;

    @BeforeEach
    void setUp() {
        this.tools = new TempoTools(this.client, new TraceAnalyzer(this.properties),
                new TraceCacheService(this.properties), this.exploreLinks, this.environments,
                this.properties);
        when(this.environments.resolve(any())).thenReturn(TARGET);
        when(this.client.resolveDatasourceUid(any(), any())).thenReturn(UID);
        when(this.exploreLinks.explore(any(), anyString(), anyString(), anyMap(), anyLong(), anyLong()))
                .thenReturn("https://grafana.test/goto/abc");
    }

    @Test
    void searchProjectsHitsWithoutInliningTraces() {
        when(this.client.searchTraces(eq(TARGET), eq(UID), anyString(), anyLong(), anyLong(), eq(20)))
                .thenReturn(MAPPER.readTree("""
                        {"traces":[{"traceID":"abc123","rootServiceName":"web",
                          "rootTraceName":"POST /checkout","startTimeUnixNano":"1786389004000000000",
                          "durationMs":254,"spanSet":{"matched":2,
                          "spans":[{"spanID":"s1","name":"POST /checkout"}]}}]}"""));

        TraceSearchResult result = this.tools.searchTraces("{ duration > 200ms }", "-1h", "now",
                null, null, null);

        assertThat(result.returned()).isEqualTo(1);
        assertThat(result.limitReached()).isFalse();
        TraceSearchResult.TraceHit hit = result.traces().getFirst();
        assertThat(hit.traceId()).isEqualTo("abc123");
        assertThat(hit.rootService()).isEqualTo("web");
        assertThat(hit.durationMs()).isEqualTo(254d);
        assertThat(hit.matchedSpans()).isEqualTo(2);
        assertThat(hit.startTime()).isEqualTo("2026-08-10T19:10:04Z");
        assertThat(result.grafanaExploreUrl()).contains("goto");
    }

    @Test
    void anEmptySearchSaysTheQueryWasValidButMatchedNothing() {
        when(this.client.searchTraces(any(), anyString(), anyString(), anyLong(), anyLong(), anyInt()))
                .thenReturn(MAPPER.readTree("{\"traces\":[]}"));

        TraceSearchResult result = this.tools.searchTraces("{ duration > 1h }", null, null, null, null, null);

        assertThat(result.returned()).isZero();
        assertThat(result.notice()).contains("valid but matched no traces").contains("sampling");
    }

    @Test
    void anUnscopedAttributeIsExplainedRatherThanRelayed() {
        // Tempo rejects it with "unknown identifier: service", which does not tell you the fix.
        when(this.client.searchTraces(any(), anyString(), anyString(), anyLong(), anyLong(), anyInt()))
                .thenThrow(new GrafanaApiException(400,
                        "invalid TraceQL query: parse error at line 1, col 3: unknown identifier: service"));

        assertThatThrownBy(() -> this.tools.searchTraces("{ service.name = \"web\" }", null, null,
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource.service.name")
                .hasMessageContaining("span.http.route");
    }

    @Test
    void searchLimitIsClampedAndReportedWhenReached() {
        when(this.client.searchTraces(eq(TARGET), eq(UID), anyString(), anyLong(), anyLong(), eq(100)))
                .thenReturn(MAPPER.readTree("{\"traces\":[{\"traceID\":\"a\"}]}"));

        TraceSearchResult result = this.tools.searchTraces("{}", null, null, 5000, null, null);

        verify(this.client).searchTraces(eq(TARGET), eq(UID), anyString(), anyLong(), anyLong(),
                eq(100));
        assertThat(result.limitReached()).isFalse();
    }

    @Test
    void getTraceReturnsAnAnalysisAndTheTraceQlExploreLink() {
        when(this.client.trace(eq(TARGET), eq(UID), eq("abc123"), anyLong(), anyLong()))
                .thenReturn(MAPPER.readTree(TRACE));

        TraceSummary summary = this.tools.getTrace("abc123", null, null, null, null);

        assertThat(summary.traceId()).isEqualTo("abc123");
        assertThat(summary.rootService()).isEqualTo("web");
        assertThat(summary.spanCount()).isEqualTo(2);
        assertThat(summary.criticalPath()).extracting(TraceSummary.CriticalPathStep::name)
                .containsExactly("POST /checkout", "INSERT orders");
        assertThat(summary.errorOrigins()).hasSize(1);
        assertThat(summary.errorOrigins().getFirst().service()).isEqualTo("db");
        assertThat(summary.logSearch().suggestedQueries())
                .contains("{service_name=\"web\"} |= `abc123`");

        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.captor();
        verify(this.exploreLinks).explore(eq(TARGET), eq(UID), eq("tempo"), fields.capture(),
                anyLong(), anyLong());
        assertThat(fields.getValue()).containsEntry("queryType", "traceql")
                .containsEntry("query", "abc123");
    }

    @Test
    void spanDetailIsServedFromTheCacheWithoutASecondFetch() {
        when(this.client.trace(eq(TARGET), eq(UID), eq("abc123"), anyLong(), anyLong()))
                .thenReturn(MAPPER.readTree(TRACE));
        this.tools.getTrace("abc123", null, null, null, null);

        TraceSpanListing all = this.tools.getTraceSpans("abc123", null, null, null, null, null);
        TraceSpanListing failed = this.tools.getTraceSpans("abc123", null, null, true, null, null);
        TraceSpanListing slow = this.tools.getTraceSpans("abc123", null, 50d, null, null, null);
        TraceSpanListing byService = this.tools.getTraceSpans("abc123", "db", null, null, null, null);

        assertThat(all.returned()).isEqualTo(2);
        assertThat(all.total()).isEqualTo(2);
        assertThat(failed.returned()).isEqualTo(1);
        assertThat(failed.spans().getFirst().statusMessage()).isEqualTo("deadlock");
        assertThat(slow.returned()).isEqualTo(2);
        assertThat(byService.spans()).extracting(TraceSpanListing.SpanView::service)
                .containsExactly("db");
        assertThat(byService.filter()).contains("service=db");
        // One Tempo call for four tool invocations: a trace is immutable, so the parse is reusable.
        verify(this.client, times(1)).trace(any(), anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void spanListingsHaveAModestDefaultAndAHardCap() {
        when(this.client.trace(eq(TARGET), eq(UID), eq("abc123"), anyLong(), anyLong()))
                .thenReturn(MAPPER.readTree(TRACE));
        this.tools.getTrace("abc123", null, null, null, null);

        // Default and max differ, as everywhere else here: a request for more than the cap is
        // clamped, and no limit at all does not mean "the maximum".
        assertThat(this.tools.getTraceSpans("abc123", null, null, null, 9999, null).returned())
                .isEqualTo(2);
        assertThat(this.tools.getTraceSpans("abc123", null, null, null, 1, null).returned()).isEqualTo(1);
        assertThat(this.tools.getTraceSpans("abc123", null, null, null, 1, null).truncated()).isTrue();
    }

    @Test
    void spanDetailForAnUncachedTraceSaysToFetchItFirst() {
        assertThatThrownBy(() -> this.tools.getTraceSpans("deadbeef", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("get_trace(traceId) first");
        verify(this.client, never()).trace(any(), anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void traceIdsAreValidatedButShortOnesAreAccepted() {
        when(this.client.trace(any(), anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(MAPPER.readTree("{\"trace\":{\"services\":[]}}"));

        // Tempo strips leading zeros, so a 31-character id is normal and must not be rejected.
        assertThat(this.tools.getTrace("d1906808ed7268fe704537a74056e48", null, null, null, null)).isNotNull();
        assertThatThrownBy(() -> this.tools.getTrace("not-a-trace-id", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hex characters");
    }

    @Test
    void metricsQueryDefaultsTheStepAndBoundsSeries() {
        when(this.client.metricsQueryRange(eq(TARGET), eq(UID), anyString(), anyLong(), anyLong(),
                anyString()))
                .thenReturn(MAPPER.readTree("""
                        {"series":[
                          {"labels":[{"key":"service","value":{"stringValue":"web"}}],
                           "samples":[{"timestampMs":"1786389004000","value":3},{"timestampMs":"1786389064000"}]}
                        ]}"""));

        TraceMetricsResult result = this.tools.queryTraceMetrics("{} | rate()", "-1h", "now", null,
                null, null);

        ArgumentCaptor<String> step = ArgumentCaptor.forClass(String.class);
        verify(this.client).metricsQueryRange(eq(TARGET), eq(UID), anyString(), anyLong(), anyLong(),
                step.capture());
        assertThat(step.getValue()).isEqualTo("60s");
        assertThat(result.seriesReturned()).isEqualTo(1);
        assertThat(result.series().getFirst().labels()).containsEntry("service", "web");
        // protobuf-JSON omits a zero value, so the second sample reads as 0 rather than failing.
        assertThat(result.series().getFirst().points())
                .containsExactly(List.of(1786389004000d, 3d), List.of(1786389064000d, 0d));
        assertThat(result.series().getFirst().total()).isEqualTo(3d);
    }

    @Test
    void queriesAndAttributeNamesAreRequired() {
        assertThatThrownBy(() -> this.tools.searchTraces("  ", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TraceQL query is required");
        assertThatThrownBy(() -> this.tools.listTraceAttributeValues("", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource.service.name");
        assertThatThrownBy(() -> this.tools.searchTraces("{}", "now", "-1h", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be before end");
    }

    @Test
    void attributeDiscoveryDelegatesToTempo() {
        JsonNode scopes = MAPPER.readTree("{\"scopes\":[{\"name\":\"resource\",\"tags\":[]}]}");
        when(this.client.tags(eq(TARGET), eq(UID), eq("resource"), anyLong(), anyLong())).thenReturn(scopes);
        when(this.client.tagValues(eq(TARGET), eq(UID), eq("resource.service.name"), anyLong(),
                anyLong()))
                .thenReturn(MAPPER.readTree("{\"tagValues\":[{\"value\":\"web\"}]}"));

        assertThat(this.tools.listTraceAttributes("resource", null, null, null, null)).isEqualTo(scopes);
        assertThat(this.tools.listTraceAttributeValues("resource.service.name", null, null, null, null)
                .path("tagValues").path(0).path("value").asString()).isEqualTo("web");
    }
}
