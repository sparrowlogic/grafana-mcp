package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.DatasourceProxy;
import com.sparrowlogic.grafanamcp.client.ExploreLinks;
import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments.Target;
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
    private final ExploreLinks exploreLinks;
    private final DatasourceProxy proxy;
    private final GrafanaEnvironments environments;

    public NodeMetricsTools(GrafanaClient client, ExploreLinks exploreLinks, DatasourceProxy proxy,
            GrafanaEnvironments environments) {
        this.client = client;
        this.exploreLinks = exploreLinks;
        this.proxy = proxy;
        this.environments = environments;
    }

    @Tool(name = "fetch_cpu",
            description = "CPU utilization for an instance over a range: percent busy (0-100) "
                    + "averaged across cores. Include the grafanaExploreUrl.")
    public Map<String, Object> fetchCpu(
            @ToolParam(description = "Datasource UID. Optional; discovered by type. A legacy numeric id also "
                    + "works.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Instance identifier, e.g. '10.0.0.5:9100'. Port :9100 is appended if "
                    + "omitted.")
            String instance,
            @ToolParam(description = "Range start: relative (-6h), 'now', a date, ISO-8601, or epoch seconds.")
            String start,
            @ToolParam(description = "Range end, same formats.")
            String end,
            @ToolParam(description = "Resolution step, e.g. '60s'. Default 60s.",
                    required = false)
            String step,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        String inst = normalizeInstance(instance);
        String promql = "100 - (avg by (instance)(rate(node_cpu_seconds_total{mode=\"idle\","
                + "instance=\"" + inst + "\"}[5m])) * 100)";
        return this.executeQuery(datasourceUid, environment, promql, start, end, step);
    }

    @Tool(name = "fetch_memory_utilization",
            description = "Memory utilization for an instance over a range: percent in use (0-100). "
                    + "Include the grafanaExploreUrl.")
    public Map<String, Object> fetchMemoryUtilization(
            @ToolParam(description = "Datasource UID. Optional; discovered by type. A legacy numeric id also "
                    + "works.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Instance identifier, e.g. '10.0.0.5:9100'.")
            String instance,
            @ToolParam(description = "Range start: relative (-6h), 'now', a date, ISO-8601, or epoch seconds.")
            String start,
            @ToolParam(description = "Range end, same formats.")
            String end,
            @ToolParam(description = "Resolution step, e.g. '60s'. Default 60s.",
                    required = false)
            String step,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        String inst = normalizeInstance(instance);
        String promql = "(1 - node_memory_MemAvailable_bytes{instance=\"" + inst + "\"}"
                + " / node_memory_MemTotal_bytes{instance=\"" + inst + "\"}) * 100";
        return this.executeQuery(datasourceUid, environment, promql, start, end, step);
    }

    @Tool(name = "fetch_load",
            description = "System load averages (1m, 5m, 15m) for an instance, normalized by CPU "
                    + "count (100 = all CPUs fully loaded). Include the grafanaExploreUrl.")
    public Map<String, Object> fetchLoad(
            @ToolParam(description = "Datasource UID. Optional; discovered by type. A legacy numeric id also "
                    + "works.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Instance identifier, e.g. '10.0.0.5:9100'.")
            String instance,
            @ToolParam(description = "Range start: relative (-6h), 'now', a date, ISO-8601, or epoch seconds.")
            String start,
            @ToolParam(description = "Range end, same formats.")
            String end,
            @ToolParam(description = "Resolution step, e.g. '60s'. Default 60s.",
                    required = false)
            String step,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        String inst = normalizeInstance(instance);
        String promql = "node_load1{instance=\"" + inst + "\"}"
                + " / count without (cpu)(node_cpu_seconds_total{instance=\""
                + inst + "\",mode=\"idle\"}) * 100";
        return this.executeQuery(datasourceUid, environment, promql, start, end, step);
    }

    @Tool(name = "fetch_disk_volumes",
            description = "Disk space utilization per mounted volume on an instance: percent used per "
                    + "mountpoint. Include the grafanaExploreUrl.")
    public Map<String, Object> fetchDiskVolumes(
            @ToolParam(description = "Datasource UID. Optional; discovered by type. A legacy numeric id also "
                    + "works.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Instance identifier, e.g. '10.0.0.5:9100'.")
            String instance,
            @ToolParam(description = "Range start: relative (-6h), 'now', a date, ISO-8601, or epoch seconds.")
            String start,
            @ToolParam(description = "Range end, same formats.")
            String end,
            @ToolParam(description = "Resolution step, e.g. '60s'. Default 60s.",
                    required = false)
            String step,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        String inst = normalizeInstance(instance);
        String promql = "(1 - node_filesystem_avail_bytes{instance=\"" + inst
                + "\",fstype!~\"tmpfs|overlay\"}"
                + " / node_filesystem_size_bytes{instance=\"" + inst
                + "\",fstype!~\"tmpfs|overlay\"}) * 100";
        return this.executeQuery(datasourceUid, environment, promql, start, end, step);
    }

    @Tool(name = "fetch_io_by_disk_volume",
            description = "Disk IO utilization per device on an instance: percent of time each device "
                    + "is busy with IO (0-100). Include the grafanaExploreUrl.")
    public Map<String, Object> fetchIoByDiskVolume(
            @ToolParam(description = "Datasource UID. Optional; discovered by type. A legacy numeric id also "
                    + "works.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Instance identifier, e.g. '10.0.0.5:9100'.")
            String instance,
            @ToolParam(description = "Range start: relative (-6h), 'now', a date, ISO-8601, or epoch seconds.")
            String start,
            @ToolParam(description = "Range end, same formats.")
            String end,
            @ToolParam(description = "Resolution step, e.g. '60s'. Default 60s.",
                    required = false)
            String step,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        String inst = normalizeInstance(instance);
        String promql = "rate(node_disk_io_time_seconds_total{instance=\"" + inst + "\"}[5m]) * 100";
        return this.executeQuery(datasourceUid, environment, promql, start, end, step);
    }

    private Map<String, Object> executeQuery(String datasourceUid, String environment, String promql,
            String start, String end, String step) {
        long startEpoch = MetricsTools.parseEpochSeconds(start);
        long endEpoch = MetricsTools.parseEpochSeconds(end);
        String resolvedStep = (step == null || step.isBlank()) ? DEFAULT_STEP : step;
        Target target = this.environments.resolve(environment);
        String uid = this.proxy.resolveUid(target, datasourceUid, MetricsTools.PROMETHEUS);
        JsonNode data = this.client.queryPrometheus(target, uid, promql, startEpoch, endEpoch,
                resolvedStep);
        String exploreUrl = MetricsTools.exploreUrl(this.exploreLinks, target, uid, promql, startEpoch,
                endEpoch);
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

    private static String normalizeInstance(String instance) {
        if (instance == null || instance.isBlank()) {
            return "";
        }
        String value = instance.trim();
        return value.contains(":") ? value : value + ":9100";
    }
}
