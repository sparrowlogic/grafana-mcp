package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.domain.Dashboard;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardToolsTest {

    private static final GrafanaEnvironments.Target TARGET =
            new GrafanaEnvironments.Target("production", "https://grafana.test", null);

    @Mock
    GrafanaClient client;

    @Mock
    GrafanaEnvironments environments;

    @InjectMocks
    DashboardTools tools;

    @BeforeEach
    void resolveEnvironment() {
        lenient().when(environments.resolve(any())).thenReturn(TARGET);
    }

    @Test
    void searchDashboardsIncludesGrafanaUrl() {
        List<Dashboard> dashboards = List.of(
                new Dashboard(1, "uid1", "Test", "/d/uid1/test", "dash-db", List.of("infra"), false));
        when(client.searchDashboards(TARGET, "test", 0)).thenReturn(dashboards);

        List<Map<String, Object>> result = tools.searchDashboards("test", null, null);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("grafanaUrl")).isEqualTo("https://grafana.test/d/uid1/test");
        assertThat(result.getFirst().get("uid")).isEqualTo("uid1");
    }

    @Test
    void dashboardUrlsPointAtTheResolvedEnvironment() {
        GrafanaEnvironments.Target local = new GrafanaEnvironments.Target("local",
                "http://localhost:3000", null);
        when(environments.resolve("local")).thenReturn(local);
        when(client.getDashboard(local, "abc")).thenReturn(JsonNodeFactory.instance.objectNode());

        Map<String, Object> result = tools.getDashboard("abc", "local");

        assertThat(result.get("grafanaUrl")).isEqualTo("http://localhost:3000/d/abc");
    }

    @Test
    void searchDashboardsWithLimit() {
        when(client.searchDashboards(TARGET, "q", 10)).thenReturn(List.of());

        List<Map<String, Object>> result = tools.searchDashboards("q", 10, null);
        assertThat(result).isEmpty();
    }

    @Test
    void getDashboardIncludesGrafanaUrl() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("title", "Dashboard");
        when(client.getDashboard(TARGET, "abc")).thenReturn(node);

        Map<String, Object> result = tools.getDashboard("abc", null);
        assertThat(result.get("grafanaUrl")).isEqualTo("https://grafana.test/d/abc");
        assertThat(result.get("dashboard")).isEqualTo(node);
    }
}
