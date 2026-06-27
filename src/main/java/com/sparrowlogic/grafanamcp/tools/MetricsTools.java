package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaApiException;
import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.config.GrafanaProperties;
import com.sparrowlogic.grafanamcp.domain.Datasource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * MCP tools for Grafana datasource and Prometheus metric queries.
 */
@Component
public class MetricsTools {

    private final GrafanaClient client;
    private final GrafanaProperties properties;

    public MetricsTools(GrafanaClient client, GrafanaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Tool(name = "list_datasources",
            description = "List all configured Grafana datasources. Use this to find the "
                    + "Prometheus datasource ID needed for query_prometheus. Returns each "
                    + "datasource's id, name, type, and whether it is the default.")
    public List<Datasource> listDatasources() {
        return this.client.listDatasources();
    }

    @Tool(name = "query_prometheus",
            description = "Execute a PromQL range query via Grafana's datasource proxy. "
                    + "Returns time-series data from Prometheus plus a Grafana Explore link. "
                    + "Use this to analyze node_exporter metrics for CPU, memory, and IO "
                    + "utilization.\n\n"
                    + "Example PromQL queries:\n"
                    + "- CPU usage: 100 - (avg by (instance)"
                    + "(rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)\n"
                    + "- Memory usage: (1 - node_memory_MemAvailable_bytes / "
                    + "node_memory_MemTotal_bytes) * 100\n"
                    + "- Disk IO utilization: rate(node_disk_io_time_seconds_total[5m]) * 100\n"
                    + "- Network receive: rate(node_network_receive_bytes_total[5m])\n\n"
                    + "Times accept ISO-8601 (e.g. 2026-06-25T00:00:00Z) or Unix epoch seconds.\n\n"
                    + "IMPORTANT: Always include the returned grafanaExploreUrl in your response "
                    + "so the user can view the query in Grafana.")
    public Map<String, Object> queryPrometheus(
            @ToolParam(description = "Datasource ID (integer, from list_datasources).")
            int datasourceId,
            @ToolParam(description = "PromQL query expression.")
            String query,
            @ToolParam(description = "Range start (ISO-8601 or Unix epoch seconds).")
            String start,
            @ToolParam(description = "Range end (ISO-8601 or Unix epoch seconds).")
            String end,
            @ToolParam(description = "Query resolution step (e.g. '60s', '5m'). "
                    + "Default: 60s.", required = false)
            String step) {
        long startEpoch = parseEpochSeconds(start);
        long endEpoch = parseEpochSeconds(end);
        JsonNode data = this.client.queryPrometheus(datasourceId, query, startEpoch, endEpoch, step);
        String exploreUrl = this.buildExploreShortUrl(datasourceId, query, startEpoch, endEpoch);
        return Map.of(
                "data", data,
                "grafanaExploreUrl", exploreUrl);
    }

    /**
     * Builds a Grafana Explore short URL via the short URL API.
     */
    private String buildExploreShortUrl(int datasourceId, String promql,
            long startEpoch, long endEpoch) {
        long startMs = startEpoch * 1000L;
        long endMs = endEpoch * 1000L;
        String json = "{\"datasource\":{\"uid\":\"__ds_" + datasourceId + "__\",\"type\":\"prometheus\"},"
                + "\"queries\":[{\"expr\":\"" + promql.replace("\"", "\\\"") + "\","
                + "\"range\":true,\"refId\":\"A\"}],"
                + "\"range\":{\"from\":\"" + startMs + "\",\"to\":\"" + endMs + "\"}}";
        String path = "explore?orgId=1&left=" + strictEncode(json);
        try {
            return this.client.createShortUrl(path);
        } catch (GrafanaApiException e) {
            return this.properties.baseUrl() + "/" + path;
        }
    }

    private static String strictEncode(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '!' || c == '~' || c == '\'') {
                sb.append(c);
            } else {
                sb.append(String.format("%%%02X", b));
            }
        }
        return sb.toString();
    }

    /**
     * Parses a time string as either Unix epoch seconds or ISO-8601 → epoch seconds.
     */
    static long parseEpochSeconds(String input) {
        if (input == null || input.isBlank()) {
            return Instant.now().getEpochSecond();
        }
        String value = input.trim();
        return value.matches("\\d+") ? Long.parseLong(value) : Instant.parse(value).getEpochSecond();
    }
}
