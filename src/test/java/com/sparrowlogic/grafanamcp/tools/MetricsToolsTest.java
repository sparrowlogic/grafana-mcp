package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.DatasourceProxy;
import com.sparrowlogic.grafanamcp.client.ExploreLinks;
import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.config.PrometheusProperties;
import com.sparrowlogic.grafanamcp.domain.Datasource;
import com.sparrowlogic.grafanamcp.domain.MetricQueryResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsToolsTest {

    private static final GrafanaEnvironments.Target TARGET =
            new GrafanaEnvironments.Target("production", "https://grafana.test", null);

    @Mock
    GrafanaClient client;

    @Mock
    ExploreLinks exploreLinks;

    @Mock
    DatasourceProxy proxy;

    @Mock
    GrafanaEnvironments environments;

    MetricsTools tools;

    @BeforeEach
    void setUp() {
        // Real properties rather than a mock: the caps are the behaviour under test.
        this.tools = new MetricsTools(client, exploreLinks, proxy, environments,
                new PrometheusProperties(null, 25, 60));
        lenient().when(environments.resolve(any())).thenReturn(TARGET);
        lenient().when(proxy.resolveUid(any(), any(), anyString())).thenReturn("prom-uid");
    }

    @Test
    void listDatasourcesDelegatesToClient() {
        List<Datasource> expected = List.of(
                new Datasource(1, "uid1", "Prometheus", "prometheus", "http://prom:9090", true));
        when(client.listDatasources(TARGET)).thenReturn(expected);

        List<Datasource> result = tools.listDatasources(null);
        assertThat(result).isEqualTo(expected);
        verify(client).listDatasources(TARGET);
    }

    /** A matrix with two series: one flat, one spiky, so ranking and stats are observable. */
    private static JsonNode matrix() {
        return JsonMapper.builder().build().readTree("""
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"instance":"flat"},"values":[[100,"1"],[160,"1"],[220,"1"]]},
                  {"metric":{"instance":"spiky"},"values":[[100,"1"],[160,"90"],[220,"3"]]}
                ]}}""");
    }

    @Test
    void queryPrometheusSummarisesSeriesAndIncludesExploreUrl() {
        long expectedStart = Instant.parse("2026-06-25T00:00:00Z").getEpochSecond();
        long expectedEnd = Instant.parse("2026-06-25T12:00:00Z").getEpochSecond();
        when(client.queryPrometheus(TARGET, "prom-uid", "up", expectedStart, expectedEnd, "5m"))
                .thenReturn(matrix());
        when(exploreLinks.explore(any(), anyString(), anyString(), anyMap(), anyLong(), anyLong()))
                .thenReturn("https://grafana.test/goto/abc");

        MetricQueryResult result = tools.queryPrometheus("prom-uid", "up",
                "2026-06-25T00:00:00Z", "2026-06-25T12:00:00Z", "5m", null, null);

        assertThat(result.seriesReturned()).isEqualTo(2);
        assertThat(result.totalPoints()).isEqualTo(6L);
        assertThat(result.pointAggregation()).isEqualTo("none");
        // Ranked by peak, so the spiky series leads.
        assertThat(result.series().getFirst().labels()).containsEntry("instance", "spiky");
        assertThat(result.series().getFirst().stats().max()).isEqualTo(90d);
        assertThat(result.series().getFirst().stats().last()).isEqualTo(3d);
        assertThat(result.series().getFirst().points()).isNull();
        assertThat(result.notice()).contains("detail='points'");
        assertThat(result.grafanaExploreUrl()).contains("grafana.test/goto");
    }

    @Test
    void detailPointsReturnsTheSamples() {
        when(client.queryPrometheus(any(), anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(matrix());
        when(exploreLinks.explore(any(), anyString(), anyString(), anyMap(), anyLong(), anyLong()))
                .thenReturn("https://grafana.test/goto/abc");

        MetricQueryResult result = tools.queryPrometheus(null, "up", "1000", "2000", null, "points", null);

        assertThat(result.pointAggregation()).isEqualTo("raw");
        assertThat(result.series().getFirst().points())
                .containsExactly(List.of(100d, 1d), List.of(160d, 90d), List.of(220d, 3d));
    }

    @Test
    void relativeSpansAreAcceptedByTheMetricTools() {
        when(client.queryPrometheus(any(), anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(matrix());
        when(exploreLinks.explore(any(), anyString(), anyString(), anyMap(), anyLong(), anyLong()))
                .thenReturn("https://grafana.test/goto/abc");

        // '-30m' used to fail with a bare parse error; the log and metric tools now share one
        // time vocabulary.
        assertThat(tools.queryPrometheus(null, "up", "-30m", "now", null, null, null)).isNotNull();
    }

    @Test
    void parseEpochSecondsHandlesIso8601() {
        long epoch = MetricsTools.parseEpochSeconds("2026-01-01T00:00:00Z");
        long expected = java.time.Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();
        assertThat(epoch).isEqualTo(expected);
    }

    @Test
    void parseEpochSecondsHandlesNumeric() {
        long epoch = MetricsTools.parseEpochSeconds("1234567890");
        assertThat(epoch).isEqualTo(1234567890L);
    }

    @Test
    void parseEpochSecondsHandlesNull() {
        long epoch = MetricsTools.parseEpochSeconds(null);
        assertThat(epoch).isGreaterThan(0);
    }

    @Test
    void parseEpochSecondsHandlesBlank() {
        long epoch = MetricsTools.parseEpochSeconds("  ");
        assertThat(epoch).isGreaterThan(0);
    }
}
