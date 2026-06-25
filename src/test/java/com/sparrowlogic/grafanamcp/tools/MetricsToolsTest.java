package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.config.GrafanaProperties;
import com.sparrowlogic.grafanamcp.domain.Datasource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsToolsTest {

    @Mock
    GrafanaClient client;

    @Mock
    GrafanaProperties properties;

    @InjectMocks
    MetricsTools tools;

    @Test
    void listDatasourcesDelegatesToClient() {
        List<Datasource> expected = List.of(
                new Datasource(1, "uid1", "Prometheus", "prometheus", "http://prom:9090", true));
        when(client.listDatasources()).thenReturn(expected);

        List<Datasource> result = tools.listDatasources();
        assertThat(result).isEqualTo(expected);
        verify(client).listDatasources();
    }

    @Test
    void queryPrometheusIncludesExploreUrl() {
        long expectedStart = java.time.Instant.parse("2026-06-25T00:00:00Z").getEpochSecond();
        long expectedEnd = java.time.Instant.parse("2026-06-25T12:00:00Z").getEpochSecond();
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("status", "success");
        when(client.queryPrometheus(1, "up", expectedStart, expectedEnd, "5m"))
                .thenReturn(node);
        when(client.createShortUrl(anyString())).thenReturn("https://grafana.test/goto/abc");

        Map<String, Object> result = tools.queryPrometheus(1, "up",
                "2026-06-25T00:00:00Z", "2026-06-25T12:00:00Z", "5m");
        assertThat(result.get("data")).isEqualTo(node);
        assertThat(result.get("grafanaExploreUrl").toString()).contains("grafana.test/goto");
    }

    @Test
    void queryPrometheusWithEpochSeconds() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("status", "success");
        when(client.queryPrometheus(2, "up", 1000L, 2000L, null)).thenReturn(node);
        when(client.createShortUrl(anyString())).thenReturn("https://grafana.test/goto/abc");

        Map<String, Object> result = tools.queryPrometheus(2, "up", "1000", "2000", null);
        assertThat(result.get("data")).isEqualTo(node);
        assertThat(result.get("grafanaExploreUrl").toString()).contains("grafana.test/goto");
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
