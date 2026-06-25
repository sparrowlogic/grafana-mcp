package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.config.GrafanaProperties;
import com.sparrowlogic.grafanamcp.domain.Dashboard;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardToolsTest {

    @Mock
    GrafanaClient client;

    @Mock
    GrafanaProperties properties;

    @InjectMocks
    DashboardTools tools;

    @Test
    void searchDashboardsIncludesGrafanaUrl() {
        when(properties.baseUrl()).thenReturn("https://grafana.test");
        List<Dashboard> dashboards = List.of(
                new Dashboard(1, "uid1", "Test", "/d/uid1/test", "dash-db", List.of("infra"), false));
        when(client.searchDashboards("test", 0)).thenReturn(dashboards);

        List<Map<String, Object>> result = tools.searchDashboards("test", null);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("grafanaUrl")).isEqualTo("https://grafana.test/d/uid1/test");
        assertThat(result.getFirst().get("uid")).isEqualTo("uid1");
    }

    @Test
    void searchDashboardsWithLimit() {
        when(client.searchDashboards("q", 10)).thenReturn(List.of());

        List<Map<String, Object>> result = tools.searchDashboards("q", 10);
        assertThat(result).isEmpty();
    }

    @Test
    void getDashboardIncludesGrafanaUrl() {
        when(properties.baseUrl()).thenReturn("https://grafana.test");
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("title", "Dashboard");
        when(client.getDashboard("abc")).thenReturn(node);

        Map<String, Object> result = tools.getDashboard("abc");
        assertThat(result.get("grafanaUrl")).isEqualTo("https://grafana.test/d/abc");
        assertThat(result.get("dashboard")).isEqualTo(node);
    }
}
