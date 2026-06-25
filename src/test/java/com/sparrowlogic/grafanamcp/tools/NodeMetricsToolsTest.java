package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.config.GrafanaProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NodeMetricsToolsTest {

    @Mock
    GrafanaClient client;

    @Mock
    GrafanaProperties properties;

    @InjectMocks
    NodeMetricsTools tools;

    private ObjectNode successNode() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("status", "success");
        ObjectNode data = node.putObject("data");
        data.put("resultType", "matrix");
        data.putArray("result");
        return node;
    }

    private ObjectNode successNodeWithData() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("status", "success");
        ObjectNode data = node.putObject("data");
        data.put("resultType", "matrix");
        var result = data.putArray("result");
        ObjectNode series = result.addObject();
        ObjectNode metric = series.putObject("metric");
        metric.put("instance", "10.0.0.1:9100");
        var values = series.putArray("values");
        // Add 10 data points with known values for predictable stats
        for (int i = 0; i < 10; i++) {
            var pair = values.addArray();
            pair.add(1000L + i * 60);
            pair.add(String.valueOf((i + 1) * 10.0)); // 10,20,30...100
        }
        return node;
    }

    @Test
    void fetchCpuWithDataReturnsStats() {
        when(client.queryPrometheus(anyInt(), anyString(), anyLong(), anyLong(), anyString()))
                .thenReturn(successNodeWithData());
        when(client.createShortUrl(anyString())).thenReturn("https://grafana.test/goto/abc");

        Map<String, Object> result = tools.fetchCpu(1, "10.0.0.1", "1000", "2000", null);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> series = (java.util.List<Map<String, Object>>) result.get("series");
        assertThat(series).hasSize(1);
        Map<String, Object> stat = series.getFirst();
        assertThat(stat.get("samples")).isEqualTo(10);
        assertThat(stat.get("min")).isEqualTo(10.0);
        assertThat(stat.get("max")).isEqualTo(100.0);
        assertThat(stat.get("avg")).isEqualTo(55.0);
        assertThat(stat.get("p95")).isNotNull();
        assertThat(stat.get("p99")).isNotNull();
        assertThat(stat.get("peakTimes")).isNotNull();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> peaks = (java.util.List<Map<String, Object>>) stat.get("peakTimes");
        assertThat(peaks).hasSize(5);
        // First peak should be highest value (100.0)
        assertThat(peaks.getFirst().get("value")).isEqualTo(100.0);
    }

    @Test
    void fetchCpuAppendsPort() {
        when(client.queryPrometheus(anyInt(), contains("172.31.1.1:9100"), anyLong(), anyLong(), anyString()))
                .thenReturn(successNode());
        when(client.createShortUrl(anyString())).thenReturn("https://grafana.test/goto/abc");

        Map<String, Object> result = tools.fetchCpu(1, "172.31.1.1", "1000", "2000", null);
        assertThat(result.get("grafanaExploreUrl").toString()).contains("grafana.test/goto");
        verify(client).queryPrometheus(eq(1), contains("172.31.1.1:9100"), eq(1000L), eq(2000L), eq("60s"));
    }

    @Test
    void fetchCpuPreservesPort() {
        when(client.queryPrometheus(anyInt(), contains("10.0.0.1:9200"), anyLong(), anyLong(), anyString()))
                .thenReturn(successNode());
        when(client.createShortUrl(anyString())).thenReturn("https://grafana.test/goto/abc");

        tools.fetchCpu(1, "10.0.0.1:9200", "1000", "2000", "5m");
        verify(client).queryPrometheus(eq(1), contains("10.0.0.1:9200"), eq(1000L), eq(2000L), eq("5m"));
    }

    @Test
    void fetchMemoryUtilization() {
        when(client.queryPrometheus(anyInt(), contains("MemAvailable"), anyLong(), anyLong(), anyString()))
                .thenReturn(successNode());
        when(client.createShortUrl(anyString())).thenReturn("https://grafana.test/goto/abc");

        Map<String, Object> result = tools.fetchMemoryUtilization(1, "10.0.0.1", "1000", "2000", null);
        assertThat(result.get("series")).isNotNull();
        verify(client).queryPrometheus(eq(1), contains("node_memory_MemAvailable_bytes"), eq(1000L), eq(2000L), eq("60s"));
    }

    @Test
    void fetchLoad() {
        when(client.queryPrometheus(anyInt(), contains("node_load1"), anyLong(), anyLong(), anyString()))
                .thenReturn(successNode());
        when(client.createShortUrl(anyString())).thenReturn("https://grafana.test/goto/abc");

        Map<String, Object> result = tools.fetchLoad(1, "10.0.0.1", "1000", "2000", null);
        assertThat(result.get("series")).isNotNull();
        verify(client).queryPrometheus(eq(1), contains("node_load1"), eq(1000L), eq(2000L), eq("60s"));
    }

    @Test
    void fetchDiskVolumes() {
        when(client.queryPrometheus(anyInt(), contains("node_filesystem_avail_bytes"), anyLong(), anyLong(), anyString()))
                .thenReturn(successNode());
        when(client.createShortUrl(anyString())).thenReturn("https://grafana.test/goto/abc");

        Map<String, Object> result = tools.fetchDiskVolumes(1, "10.0.0.1", "1000", "2000", null);
        assertThat(result.get("series")).isNotNull();
    }

    @Test
    void fetchIoByDiskVolume() {
        when(client.queryPrometheus(anyInt(), contains("node_disk_io_time"), anyLong(), anyLong(), anyString()))
                .thenReturn(successNode());
        when(client.createShortUrl(anyString())).thenReturn("https://grafana.test/goto/abc");

        Map<String, Object> result = tools.fetchIoByDiskVolume(1, "10.0.0.1", "1000", "2000", null);
        assertThat(result.get("series")).isNotNull();
    }
}
