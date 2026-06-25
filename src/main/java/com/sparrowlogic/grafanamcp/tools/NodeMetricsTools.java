package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.config.GrafanaProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Pre-baked node_exporter metric queries for common resource utilization scenarios.
 * These avoid the need to construct PromQL manually for routine checks.
 */
@Component
public class NodeMetricsTools {

    private static final String DEFAULT_STEP = "60s";

    private final GrafanaClient client;
    private final GrafanaProperties properties;

    public NodeMetricsTools(GrafanaClient client, GrafanaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Tool(name = "fetch_cpu",
            description = "Fetch CPU utilization percentage for an instance over a time range. "
                    + "Returns percent busy (0-100) averaged across all cores. Always include "
                    + "the grafanaExploreUrl in your response.")
    public Map<String, Object> fetchCpu(
            @ToolParam(description = "Prometheus datasource ID (from list_datasources).")
            int datasourceId,
            @ToolParam(description = "Instance identifier (e.g. '10.0.0.5:9100' or "
                    + "'10.0.0.5'). Port :9100 is appended if omitted.")
            String instance,
            @ToolParam(description = "Range start (ISO-8601 or Unix epoch seconds).")
            String start,
            @ToolParam(description = "Range end (ISO-8601 or Unix epoch seconds).")
            String end,
            @ToolParam(description = "Step (e.g. '60s', '5m'). Default: 60s.",
                    required = false)
            String step) {
        String inst = normalizeInstance(instance);
        String promql = "100 - (avg by (instance)(rate(node_cpu_seconds_total{mode=\"idle\","
                + "instance=\"" + inst + "\"}[5m])) * 100)";
        return this.executeQuery(datasourceId, promql, start, end, step);
    }

    @Tool(name = "fetch_memory_utilization",
            description = "Fetch memory utilization percentage for an instance over a time "
                    + "range. Returns percent of memory in use (0-100). Always include "
                    + "the grafanaExploreUrl in your response.")
    public Map<String, Object> fetchMemoryUtilization(
            @ToolParam(description = "Prometheus datasource ID (from list_datasources).")
            int datasourceId,
            @ToolParam(description = "Instance identifier (e.g. '10.0.0.5:9100').")
            String instance,
            @ToolParam(description = "Range start (ISO-8601 or Unix epoch seconds).")
            String start,
            @ToolParam(description = "Range end (ISO-8601 or Unix epoch seconds).")
            String end,
            @ToolParam(description = "Step (e.g. '60s', '5m'). Default: 60s.",
                    required = false)
            String step) {
        String inst = normalizeInstance(instance);
        String promql = "(1 - node_memory_MemAvailable_bytes{instance=\"" + inst + "\"}"
                + " / node_memory_MemTotal_bytes{instance=\"" + inst + "\"}) * 100";
        return this.executeQuery(datasourceId, promql, start, end, step);
    }

    @Tool(name = "fetch_load",
            description = "Fetch system load averages (1m, 5m, 15m) for an instance over a "
                    + "time range. Returns load normalized by CPU count (0-100 scale where "
                    + "100 = all CPUs fully loaded). Always include the grafanaExploreUrl.")
    public Map<String, Object> fetchLoad(
            @ToolParam(description = "Prometheus datasource ID (from list_datasources).")
            int datasourceId,
            @ToolParam(description = "Instance identifier (e.g. '10.0.0.5:9100').")
            String instance,
            @ToolParam(description = "Range start (ISO-8601 or Unix epoch seconds).")
            String start,
            @ToolParam(description = "Range end (ISO-8601 or Unix epoch seconds).")
            String end,
            @ToolParam(description = "Step (e.g. '60s', '5m'). Default: 60s.",
                    required = false)
            String step) {
        String inst = normalizeInstance(instance);
        String promql = "node_load1{instance=\"" + inst + "\"}"
                + " / count without (cpu)(node_cpu_seconds_total{instance=\""
                + inst + "\",mode=\"idle\"}) * 100";
        return this.executeQuery(datasourceId, promql, start, end, step);
    }

    @Tool(name = "fetch_disk_volumes",
            description = "Fetch disk space utilization for all mounted volumes on an "
                    + "instance. Returns percent used per mountpoint. Always include "
                    + "the grafanaExploreUrl in your response.")
    public Map<String, Object> fetchDiskVolumes(
            @ToolParam(description = "Prometheus datasource ID (from list_datasources).")
            int datasourceId,
            @ToolParam(description = "Instance identifier (e.g. '10.0.0.5:9100').")
            String instance,
            @ToolParam(description = "Range start (ISO-8601 or Unix epoch seconds).")
            String start,
            @ToolParam(description = "Range end (ISO-8601 or Unix epoch seconds).")
            String end,
            @ToolParam(description = "Step (e.g. '60s', '5m'). Default: 60s.",
                    required = false)
            String step) {
        String inst = normalizeInstance(instance);
        String promql = "(1 - node_filesystem_avail_bytes{instance=\"" + inst
                + "\",fstype!~\"tmpfs|overlay\"}"
                + " / node_filesystem_size_bytes{instance=\"" + inst
                + "\",fstype!~\"tmpfs|overlay\"}) * 100";
        return this.executeQuery(datasourceId, promql, start, end, step);
    }

    @Tool(name = "fetch_io_by_disk_volume",
            description = "Fetch disk IO utilization percentage per device on an instance. "
                    + "Returns the percentage of time each disk device is busy with IO "
                    + "operations (0-100). Always include the grafanaExploreUrl.")
    public Map<String, Object> fetchIoByDiskVolume(
            @ToolParam(description = "Prometheus datasource ID (from list_datasources).")
            int datasourceId,
            @ToolParam(description = "Instance identifier (e.g. '10.0.0.5:9100').")
            String instance,
            @ToolParam(description = "Range start (ISO-8601 or Unix epoch seconds).")
            String start,
            @ToolParam(description = "Range end (ISO-8601 or Unix epoch seconds).")
            String end,
            @ToolParam(description = "Step (e.g. '60s', '5m'). Default: 60s.",
                    required = false)
            String step) {
        String inst = normalizeInstance(instance);
        String promql = "rate(node_disk_io_time_seconds_total{instance=\"" + inst + "\"}[5m]) * 100";
        return this.executeQuery(datasourceId, promql, start, end, step);
    }

    private Map<String, Object> executeQuery(int datasourceId, String promql,
            String start, String end, String step) {
        long startEpoch = MetricsTools.parseEpochSeconds(start);
        long endEpoch = MetricsTools.parseEpochSeconds(end);
        String resolvedStep = (step == null || step.isBlank()) ? DEFAULT_STEP : step;
        JsonNode data = this.client.queryPrometheus(datasourceId, promql, startEpoch, endEpoch, resolvedStep);
        String exploreUrl = this.buildExploreShortUrl(promql, startEpoch, endEpoch);
        List<Map<String, Object>> series = computeStats(data);
        return Map.of(
                "series", series,
                "grafanaExploreUrl", exploreUrl);
    }

    private static List<Map<String, Object>> computeStats(JsonNode data) {
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        JsonNode resultArray = data.path("data").path("result");
        if (!resultArray.isArray()) {
            return results;
        }
        for (JsonNode series : resultArray) {
            Map<String, String> labels = new java.util.LinkedHashMap<>();
            series.path("metric").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));

            JsonNode values = series.path("values");
            int len = values.size();
            if (len == 0) {
                continue;
            }

            // Extract timestamp-value pairs
            double[] vals = new double[len];
            long[] timestamps = new long[len];
            for (int i = 0; i < len; i++) {
                timestamps[i] = values.get(i).get(0).asLong();
                vals[i] = values.get(i).get(1).asDouble();
            }

            // Sort values for percentile calculations
            double[] sorted = vals.clone();
            java.util.Arrays.sort(sorted);

            double avg = java.util.Arrays.stream(sorted).average().orElse(0);

            // Find top 5 peak moments by value (descending)
            Integer[] indices = new Integer[len];
            for (int i = 0; i < len; i++) {
                indices[i] = i;
            }
            java.util.Arrays.sort(indices, (a, b) -> Double.compare(vals[b], vals[a]));
            List<Map<String, Object>> peaks = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(5, len); i++) {
                int idx = indices[i];
                peaks.add(Map.of(
                        "time", java.time.Instant.ofEpochSecond(timestamps[idx]).toString(),
                        "value", round(vals[idx])));
            }

            Map<String, Object> stat = new java.util.LinkedHashMap<>();
            stat.put("labels", labels);
            stat.put("samples", len);
            stat.put("min", round(sorted[0]));
            stat.put("avg", round(avg));
            stat.put("p95", round(percentile(sorted, 95)));
            stat.put("p99", round(percentile(sorted, 99)));
            stat.put("max", round(sorted[len - 1]));
            stat.put("peakTimes", peaks);
            results.add(stat);
        }
        return results;
    }

    private static double percentile(double[] sorted, int pct) {
        double idx = (pct / 100.0) * (sorted.length - 1);
        int lower = (int) Math.floor(idx);
        int upper = (int) Math.ceil(idx);
        if (lower == upper) {
            return sorted[lower];
        }
        double frac = idx - lower;
        return sorted[lower] + frac * (sorted[upper] - sorted[lower]);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String buildExploreShortUrl(String promql, long startEpoch, long endEpoch) {
        long startMs = startEpoch * 1000L;
        long endMs = endEpoch * 1000L;
        String json = "{\"queries\":[{\"expr\":\""
                + promql.replace("\"", "\\\"")
                + "\",\"range\":true,\"refId\":\"A\"}],"
                + "\"range\":{\"from\":\"" + startMs + "\",\"to\":\"" + endMs + "\"}}";
        String path = "explore?orgId=1&left=" + strictEncode(json);
        return this.client.createShortUrl(path);
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

    private static String normalizeInstance(String instance) {
        if (instance == null || instance.isBlank()) {
            return "";
        }
        String value = instance.trim();
        return value.contains(":") ? value : value + ":9100";
    }
}
