package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.cache.CachedLokiSearch;
import com.sparrowlogic.grafanamcp.cache.LokiSearchCacheService;
import com.sparrowlogic.grafanamcp.client.ExploreLinks;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.client.LokiClient;
import com.sparrowlogic.grafanamcp.config.LokiProperties;
import com.sparrowlogic.grafanamcp.domain.CompactLogEntry;
import com.sparrowlogic.grafanamcp.domain.LogEntry;
import com.sparrowlogic.grafanamcp.domain.LogEntryDetail;
import com.sparrowlogic.grafanamcp.domain.LogVolumeResult;
import com.sparrowlogic.grafanamcp.domain.LokiSearchResult;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LokiToolsTest {

    private static final String UID = "loki-uid";
    private static final String QUERY = "{app=\"a\"}";
    private static final long BASE_NS = 1786385265000000000L;

    private static final GrafanaEnvironments.Target TARGET =
            new GrafanaEnvironments.Target("production", "https://grafana.test", null);

    private final LokiSearchCacheService cache = mock(LokiSearchCacheService.class);
    private final GrafanaEnvironments environments = mock(GrafanaEnvironments.class);
    private final LokiClient client = mock(LokiClient.class);
    private final ExploreLinks exploreLinks = mock(ExploreLinks.class);
    private final LokiProperties properties = new LokiProperties(null, 2000, 1000, 2, 200,
            20000, 120, 2000, 5, 30, 2, 2);

    private LokiTools tools;

    @BeforeEach
    void setUp() {
        this.tools = new LokiTools(this.cache, this.client, this.exploreLinks, this.environments,
                this.properties);
        when(this.environments.resolve(any())).thenReturn(TARGET);
        when(this.client.resolveDatasourceUid(any(), any())).thenReturn(UID);
    }

    private static CachedLokiSearch snapshot(List<LogEntry> entries, List<Map<String, String>> streams,
            boolean capped) {
        return new CachedLokiSearch("search-1", UID, QUERY, LokiSearchCacheService.BACKWARD,
                "2026-08-10T15:00:00Z", "2026-08-10T16:00:00Z", entries, streams, capped, true,
                "https://grafana.test/goto/abc", 0L, Long.MAX_VALUE);
    }

    private static CachedLokiSearch threeEntries(boolean capped) {
        return snapshot(List.of(
                new LogEntry("e0", BASE_NS + 2, 0, "newest"),
                new LogEntry("e1", BASE_NS + 1, 1, "middle"),
                new LogEntry("e2", BASE_NS, 0, "oldest")),
                List.of(Map.of("app", "a"), Map.of("app", "b")), capped);
    }

    @Test
    void searchReturnsFirstPageWithAStreamLegendForReferencedStreamsOnly() {
        when(this.cache.search(TARGET, UID, QUERY, "-1h", "now", null))
                .thenReturn(new LokiSearchCacheService.Handle(threeEntries(false), false));

        LokiSearchResult result = this.tools.searchLokiLogs(QUERY, "-1h", "now", null, null, null, null);

        assertThat(result.searchId()).isEqualTo("search-1");
        assertThat(result.page()).isZero();
        assertThat(result.pageSize()).isEqualTo(2);
        assertThat(result.returned()).isEqualTo(2);
        assertThat(result.totalFetched()).isEqualTo(3);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextPage()).isEqualTo(1);
        assertThat(result.cached()).isFalse();
        assertThat(result.entries()).extracting(CompactLogEntry::line)
                .containsExactly("newest", "middle");
        assertThat(result.entries()).extracting(CompactLogEntry::stream).containsExactly("s0", "s1");
        assertThat(result.streams()).containsOnlyKeys("s0", "s1");
        assertThat(result.streams().get("s1")).isEqualTo(Map.of("app", "b"));
        assertThat(result.coveredFrom()).isEqualTo("2026-08-10T18:07:45Z");
        assertThat(result.coveredUntil()).isEqualTo("2026-08-10T18:07:45.000000002Z");
        assertThat(result.grafanaExploreUrl()).isEqualTo("https://grafana.test/goto/abc");
        assertThat(result.notice()).isNull();
    }

    @Test
    void lastPageReportsNoMoreAndOnlyItsOwnStreams() {
        when(this.cache.get("search-1")).thenReturn(threeEntries(false));

        LokiSearchResult result = this.tools.fetchLokiPage("search-1", 1, null);

        assertThat(result.returned()).isEqualTo(1);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextPage()).isNull();
        assertThat(result.cached()).isTrue();
        assertThat(result.entries()).extracting(CompactLogEntry::line).containsExactly("oldest");
        assertThat(result.streams()).containsOnlyKeys("s0");
    }

    @Test
    void pagesPastTheEndAreEmptyRatherThanAnError() {
        when(this.cache.get("search-1")).thenReturn(threeEntries(false));

        LokiSearchResult result = this.tools.fetchLokiPage("search-1", 9, null);

        assertThat(result.entries()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void negativePagesAndOversizedPageSizesAreClamped() {
        when(this.cache.get("search-1")).thenReturn(threeEntries(false));

        assertThat(this.tools.fetchLokiPage("search-1", -5, 500).page()).isZero();
        assertThat(this.tools.fetchLokiPage("search-1", 0, 500).pageSize()).isEqualTo(200);
        assertThat(this.tools.fetchLokiPage("search-1", null, 0).pageSize()).isEqualTo(2);
    }

    @Test
    void longLinesAreTruncatedWithinThePageCharacterBudget() {
        String longLine = "x".repeat(9000);
        when(this.cache.get("search-1")).thenReturn(snapshot(
                List.of(new LogEntry("e0", BASE_NS, 0, longLine)),
                List.of(Map.of("app", "a")), false));

        LokiSearchResult result = this.tools.fetchLokiPage("search-1", 0, 1);

        String line = result.entries().getFirst().line();
        assertThat(line).startsWith("xxx").contains("[truncated — use get_loki_entry");
        assertThat(line).hasSizeLessThan(2100);
    }

    @Test
    void aCappedSnapshotSaysWhichSliceOfTheWindowItCovers() {
        when(this.cache.get("search-1")).thenReturn(threeEntries(true));

        LokiSearchResult result = this.tools.fetchLokiPage("search-1", 0, 2);

        assertThat(result.capped()).isTrue();
        assertThat(result.notice())
                .contains("Truncated at the fetch cap of 2000")
                .contains("newest")
                .contains("NOT the whole requested window")
                .contains("2026-08-10T16:00:00Z")
                .contains("count_loki_logs");
    }

    @Test
    void anEmptyResultSuggestsHowToFindTheRightSelector() {
        when(this.cache.get("search-1")).thenReturn(snapshot(List.of(), List.of(), false));

        LokiSearchResult result = this.tools.fetchLokiPage("search-1", 0, 2);

        assertThat(result.returned()).isZero();
        assertThat(result.coveredFrom()).isNull();
        assertThat(result.coveredUntil()).isNull();
        assertThat(result.notice()).contains("list_loki_labels");
    }

    @Test
    void searchRequiresAQuery() {
        assertThatThrownBy(() -> this.tools.searchLokiLogs("  ", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LogQL query is required");
    }

    @Test
    void getEntryReturnsTheFullLineAndLabels() {
        when(this.cache.get("search-1")).thenReturn(threeEntries(false));

        LogEntryDetail detail = this.tools.getLokiEntry("search-1", "e1");

        assertThat(detail.line()).isEqualTo("middle");
        assertThat(detail.labels()).isEqualTo(Map.of("app", "b"));
        assertThat(detail.timestampNs()).isEqualTo(BASE_NS + 1);
        assertThat(detail.time()).isEqualTo("2026-08-10T18:07:45.000000001Z");
    }

    @Test
    void getEntryRejectsAnUnknownId() {
        when(this.cache.get("search-1")).thenReturn(threeEntries(false));

        assertThatThrownBy(() -> this.tools.getLokiEntry("search-1", "e99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in snapshot");
    }

    @Test
    void labelsAndLabelValuesDelegateToTheClient() {
        when(this.client.labels(eq(TARGET), eq(UID), anyLong(), anyLong())).thenReturn(List.of("level"));
        when(this.client.labelValues(eq(TARGET), eq(UID), eq("level"), anyLong(), anyLong(), any()))
                .thenReturn(List.of("ERROR"));

        assertThat(this.tools.listLokiLabels("-1h", "now", null, null)).containsExactly("level");
        assertThat(this.tools.listLokiLabelValues("level", QUERY, "-1h", "now", null, null))
                .containsExactly("ERROR");
    }

    @Test
    void labelNamesAreValidatedBeforeCallingLoki() {
        assertThatThrownBy(() -> this.tools.listLokiLabelValues("bad-name!", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid Loki label name");
    }

    @Test
    void windowMustRunForwards() {
        assertThatThrownBy(() -> this.tools.listLokiLabels("now", "-1h", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be before end");
    }

    @Test
    void countWrapsTheSelectorInCountOverTimeAndGroupsByLabels() {
        when(this.client.metricQuery(eq(TARGET), eq(UID), anyString(), anyLong(), anyLong(),
                anyLong()))
                .thenReturn(matrix());
        when(this.exploreLinks.explore(eq(TARGET), eq(UID), eq("loki"), anyMap(), anyLong(),
                anyLong()))
                .thenReturn("https://grafana.test/goto/xyz");

        LogVolumeResult result = this.tools.countLokiLogs(QUERY, "-1h", "now", null,
                " service_name , level ", null, null);

        ArgumentCaptor<String> logql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> step = ArgumentCaptor.forClass(Long.class);
        verify(this.client).metricQuery(eq(TARGET), eq(UID), logql.capture(), anyLong(), anyLong(),
                step.capture());
        assertThat(logql.getValue())
                .isEqualTo("sum by (service_name, level) (count_over_time({app=\"a\"}[60s]))");
        assertThat(step.getValue()).isEqualTo(60L);
        assertThat(result.step()).isEqualTo("60s");
        assertThat(result.total()).isEqualTo(9L);
        assertThat(result.series()).hasSize(2);
        assertThat(result.series().getFirst().total()).isEqualTo(6L);
        assertThat(result.seriesTruncated()).isFalse();
        assertThat(result.grafanaExploreUrl()).isEqualTo("https://grafana.test/goto/xyz");
    }

    @Test
    void countWithoutGroupingSumsEverything() {
        when(this.client.metricQuery(eq(TARGET), eq(UID), anyString(), anyLong(), anyLong(),
                anyLong()))
                .thenReturn(matrix());

        this.tools.countLokiLogs(QUERY, "-10m", "now", "5m", null, null, null);

        ArgumentCaptor<String> logql = ArgumentCaptor.forClass(String.class);
        verify(this.client).metricQuery(eq(TARGET), eq(UID), logql.capture(), anyLong(), anyLong(),
                anyLong());
        assertThat(logql.getValue()).isEqualTo("sum(count_over_time({app=\"a\"}[300s]))");
    }

    @Test
    void countWidensAStepThatWouldProduceTooManyBuckets() {
        when(this.client.metricQuery(eq(TARGET), eq(UID), anyString(), anyLong(), anyLong(),
                anyLong()))
                .thenReturn(matrix());

        // 7 days at 1s buckets would be 604 800 points; the step is widened to stay bounded.
        LogVolumeResult result = this.tools.countLokiLogs(QUERY, "-7d", "now", "1s", null, null, null);

        assertThat(Long.parseLong(result.step().replace("s", ""))).isEqualTo(3024L);
    }

    @Test
    void countRejectsAMetricExpression() {
        assertThatThrownBy(() -> this.tools.countLokiLogs(
                "sum(rate({app=\"a\"}[5m]))", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takes a LOG query");
    }

    @Test
    void countRejectsAnInvalidGroupByLabel() {
        assertThatThrownBy(() -> this.tools.countLokiLogs(QUERY, null, null, null,
                "service_name, oops)", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid label name");
    }

    /** Two series so ordering by descending total is observable. */
    private static JsonNode matrix() {
        return JsonMapper.builder().build().readTree("""
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"service_name":"api","level":"WARN"},
                   "values":[[1786381500,"1"],[1786381800,"2"]]},
                  {"metric":{"service_name":"api","level":"ERROR"},
                   "values":[[1786381500,"5"],[1786381800,"1"]]}
                ]}}""");
    }
}
