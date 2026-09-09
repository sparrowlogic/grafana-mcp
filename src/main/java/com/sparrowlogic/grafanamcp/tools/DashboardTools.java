package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.domain.Dashboard;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * MCP tools for Grafana dashboard operations.
 */
@Component
public class DashboardTools {

    private final GrafanaClient client;
    private final GrafanaEnvironments environments;

    public DashboardTools(GrafanaClient client, GrafanaEnvironments environments) {
        this.client = client;
        this.environments = environments;
    }

    @Tool(name = "search_dashboards",
            description = "Search dashboards by title or tag. Returns uid (for get_dashboard), title, "
                    + "grafanaUrl and tags. Include grafanaUrl in your response.")
    public List<Map<String, Object>> searchDashboards(
            @ToolParam(description = "Search string matching the title. Empty string lists all.", required = false)
            String query,
            @ToolParam(description = "Max dashboards to return. Default 50.",
                    required = false)
            Integer limit,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        GrafanaEnvironments.Target target = this.environments.resolve(environment);
        List<Dashboard> dashboards = this.client.searchDashboards(target, query,
                limit == null ? 0 : limit);
        return dashboards.stream()
                .map(d -> Map.<String, Object>of(
                        "uid", d.uid(),
                        "title", d.title(),
                        "tags", d.tags(),
                        "grafanaUrl", target.baseUrl() + d.url()))
                .toList();
    }

    @Tool(name = "get_dashboard",
            description = "Full dashboard JSON by UID — panels, queries, variables — plus a "
                    + "grafanaUrl. Use search_dashboards to find the UID.")
    public Map<String, Object> getDashboard(
            @ToolParam(description = "Dashboard UID, from search_dashboards.")
            String uid,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        GrafanaEnvironments.Target target = this.environments.resolve(environment);
        JsonNode dashboard = this.client.getDashboard(target, uid);
        String url = target.baseUrl() + "/d/" + uid;
        return Map.of(
                "dashboard", dashboard,
                "grafanaUrl", url);
    }
}
