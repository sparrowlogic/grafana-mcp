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
            description = "List configured Grafana datasources with uid, id, name, type and whether "
                    + "each is the default. The metric and log tools discover the right one by "
                    + "type, so this is mainly for seeing what exists or choosing between several "
                    + "of one type.")
    public List<Datasource> listDatasources(
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        return this.client.listDatasources(this.environments.resolve(environment));
    }

    @Tool(name = "query_prometheus",
            description = "Run a PromQL range query. Returns a SUMMARY per series (point count, "
                    + "min/avg/max/last, first and last sample time) plus a Grafana Explore link "
                    + "— not raw samples, which run to hundreds of thousands of characters. "
                    + "detail='points' adds samples, bucketed down so spikes survive.\n\nSeries "
                    + "are ranked by peak and capped; if 'seriesTruncated' is true, aggregate in "
                    + "PromQL (sum by (instance) (...)) rather than paging. Include the returned "
                    + "grafanaExploreUrl in your response.")
    public MetricQueryResult queryPrometheus(
            @ToolParam(description = "Prometheus datasource UID. Optional; discovered automatically. A legacy "
                    + "numeric id also works.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "PromQL query expression.")
            String query,
            @ToolParam(description = "Range start: relative (-6h), 'now', a date, ISO-8601, or epoch seconds.")
            String start,
            @ToolParam(description = "Range end, same formats.")
            String end,
            @ToolParam(description = "Resolution step, e.g. '60s'. Default 60s.",
                    required = false)
            String step,
            @ToolParam(description = "'summary' (default) for statistics only, or 'points' to include samples.", required = false)
            String detail,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
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
