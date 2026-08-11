package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.DatasourceProxy;
import com.sparrowlogic.grafanamcp.client.ExploreLinks;
import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments.Target;
import com.sparrowlogic.grafanamcp.client.Times;
import com.sparrowlogic.grafanamcp.config.PrometheusProperties;
import com.sparrowlogic.grafanamcp.domain.Datasource;
import com.sparrowlogic.grafanamcp.domain.MetricQueryResult;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

    /** Grafana datasource type of a Prometheus datasource, for discovery and Explore links. */
    static final String PROMETHEUS = "prometheus";

    private final GrafanaClient client;
    private final ExploreLinks exploreLinks;
    private final DatasourceProxy proxy;
    private final GrafanaEnvironments environments;
    private final PrometheusProperties properties;

    public MetricsTools(GrafanaClient client, ExploreLinks exploreLinks, DatasourceProxy proxy,
            GrafanaEnvironments environments, PrometheusProperties properties) {
        this.client = client;
        this.exploreLinks = exploreLinks;
        this.proxy = proxy;
        this.environments = environments;
        this.properties = properties;
    }

    @Tool(name = "list_datasources",
            description = "List all configured Grafana datasources. Returns each datasource's uid, "
                    + "id, name, type, and whether it is the default. Datasources are addressed by "
                    + "UID; the metric and log tools discover the right one by type, so this is "
                    + "mainly for seeing what exists or picking between several of the same type.")
    public List<Datasource> listDatasources(
            @ToolParam(description = "Which Grafana to use — REQUIRED, there is no default. Run "
                    + "list_grafana_environments for the configured names; ask the user which "
                    + "one they mean rather than guessing.")
            String environment) {
        return this.client.listDatasources(this.environments.resolve(environment));
    }

    @Tool(name = "query_prometheus",
            description = "Execute a PromQL range query via Grafana's datasource proxy. Returns a "
                    + "SUMMARY of each matching series (point count, min/avg/max/last, first and last "
                    + "sample time) plus a Grafana Explore link — not the raw time series, which for "
                    + "a broad query is hundreds of series and hundreds of thousands of characters. "
                    + "Pass detail='points' when you actually need the samples; they are bucketed "
                    + "down, keeping each bucket's peak, so spikes survive.\n\n"
                    + "Series are ranked by peak value and capped. If 'seriesTruncated' is true, "
                    + "aggregate in PromQL (e.g. 'sum by (instance) (...)') rather than trying to "
                    + "page through them; read 'notice' for what was bounded.\n\n"
                    + "Example PromQL queries:\n"
                    + "- CPU usage: 100 - (avg by (instance)"
                    + "(rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)\n"
                    + "- Memory usage: (1 - node_memory_MemAvailable_bytes / "
                    + "node_memory_MemTotal_bytes) * 100\n"
                    + "- Disk IO utilization: rate(node_disk_io_time_seconds_total[5m]) * 100\n"
                    + "- Network receive: rate(node_network_receive_bytes_total[5m])\n"
                    + "- Service dependencies from traces: sum by (client, server) "
                    + "(rate(traces_service_graph_request_total[5m]))\n"
                    + "- Span latency by service: histogram_quantile(0.95, sum by (service, le) "
                    + "(rate(traces_spanmetrics_latency_bucket[5m])))\n\n"
                    + "Times accept relative spans (-30m, -6h), 'now', dates, ISO-8601 instants, or "
                    + "Unix epoch seconds.\n\n"
                    + "IMPORTANT: Always include the returned grafanaExploreUrl in your response "
                    + "so the user can view the query in Grafana.")
    public MetricQueryResult queryPrometheus(
            @ToolParam(description = "Prometheus datasource UID (from list_datasources). "
                    + "Optional — the Prometheus datasource is discovered automatically. A legacy "
                    + "numeric datasource id is also accepted and translated to its UID.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "PromQL query expression.")
            String query,
            @ToolParam(description = "Range start: a relative span (-30m, -6h, -7d), 'now', a date "
                    + "(2026-06-01), an ISO-8601 instant, or Unix epoch seconds.")
            String start,
            @ToolParam(description = "Range end, same formats.")
            String end,
            @ToolParam(description = "Query resolution step (e.g. '60s', '5m'). Default: 60s.",
                    required = false)
            String step,
            @ToolParam(description = "'summary' (default) for statistics only, or 'points' to also "
                    + "return the samples.", required = false)
            String detail,
            @ToolParam(description = "Which Grafana to use — REQUIRED, there is no default. Run "
                    + "list_grafana_environments for the configured names; ask the user which "
                    + "one they mean rather than guessing.")
            String environment) {
        long startEpoch = parseEpochSeconds(start);
        long endEpoch = parseEpochSeconds(end);
        Target target = this.environments.resolve(environment);
        String uid = this.proxy.resolveUid(target, datasourceUid, PROMETHEUS);
        JsonNode data = this.client.queryPrometheus(target, uid, query, startEpoch, endEpoch, step);
        MetricQueryResult.Bounds bounds = new MetricQueryResult.Bounds(wantsPoints(detail),
                this.properties.maxSeries(), this.properties.maxPointsPerSeries());
        return MetricQueryResult.from(data, query, step == null || step.isBlank() ? "60s" : step,
                Instant.ofEpochSecond(startEpoch).toString(), Instant.ofEpochSecond(endEpoch).toString(),
                bounds, exploreUrl(this.exploreLinks, target, uid, query, startEpoch, endEpoch));
    }

    /** Samples are opt-in: anything but an explicit points/samples/full request stays summarised. */
    private static boolean wantsPoints(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String value = detail.trim().toLowerCase(Locale.ROOT);
        return "points".equals(value) || "samples".equals(value) || "full".equals(value);
    }

    /**
     * Explore link for a PromQL range query. Prometheus's query model uses a boolean {@code range}
     * flag rather than Loki's {@code queryType}, which is why the fields are passed in rather than
     * assumed.
     */
    static String exploreUrl(ExploreLinks links, Target target, String datasourceUid, String promql,
            long startEpoch, long endEpoch) {
        return links.explore(target, datasourceUid, PROMETHEUS,
                Map.of("expr", promql, "range", true),
                startEpoch * 1000L, endEpoch * 1000L);
    }

    /**
     * Resolves a time to epoch seconds for Prometheus using the same vocabulary as the log tools
     * (see {@link Times}): a relative span, {@code now}, a date, an ISO-8601 instant, or epoch
     * seconds. The metric tools originally took only the last two, so {@code -30m} — the obvious
     * thing to reach for, and what the log tools accept — failed with a bare parse error.
     */
    static long parseEpochSeconds(String input) {
        return Times.resolve(input, Instant.now()).getEpochSecond();
    }
}
