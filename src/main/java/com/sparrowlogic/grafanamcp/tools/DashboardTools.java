package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.config.GrafanaProperties;
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
    private final GrafanaProperties properties;

    public DashboardTools(GrafanaClient client, GrafanaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Tool(name = "search_dashboards",
            description = "Search Grafana dashboards by title or tag. Returns a list of "
                    + "matching dashboards with their uid (needed for get_dashboard), title, "
                    + "grafanaUrl (full link to view in browser), and tags. Always include "
                    + "grafanaUrl in your response so users can click through to Grafana.")
    public List<Map<String, Object>> searchDashboards(
            @ToolParam(description = "Search query string (matches title). "
                    + "Use empty string to list all.", required = false)
            String query,
            @ToolParam(description = "Max dashboards to return (default 50).",
                    required = false)
            Integer limit) {
        List<Dashboard> dashboards = this.client.searchDashboards(query, limit == null ? 0 : limit);
        return dashboards.stream()
                .map(d -> Map.<String, Object>of(
                        "uid", d.uid(),
                        "title", d.title(),
                        "tags", d.tags(),
                        "grafanaUrl", this.properties.baseUrl() + d.url()))
                .toList();
    }

    @Tool(name = "get_dashboard",
            description = "Get the full dashboard JSON by UID. Returns the complete "
                    + "dashboard model including panels, queries, variables, and a "
                    + "grafanaUrl link to view it in the browser. Use search_dashboards "
                    + "first to find the UID.")
    public Map<String, Object> getDashboard(
            @ToolParam(description = "Dashboard UID (from search_dashboards).")
            String uid) {
        JsonNode dashboard = this.client.getDashboard(uid);
        String url = this.properties.baseUrl() + "/d/" + uid;
        return Map.of(
                "dashboard", dashboard,
                "grafanaUrl", url);
    }
}
