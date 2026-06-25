package com.sparrowlogic.grafanamcp.client;

import com.sparrowlogic.grafanamcp.config.GrafanaProperties;
import com.sparrowlogic.grafanamcp.domain.Annotation;
import com.sparrowlogic.grafanamcp.domain.Dashboard;
import com.sparrowlogic.grafanamcp.domain.Datasource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GrafanaClientTest {

    private MockRestServiceServer mockServer;
    private GrafanaClient client;

    @BeforeEach
    void setUp() {
        GrafanaProperties properties = new GrafanaProperties(
                "test-token", "https://grafana.test", 50, 100);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl());
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        client = new GrafanaClient(restClient, properties);
    }

    @Test
    void searchDashboards() {
        mockServer.expect(requestTo("https://grafana.test/api/search?type=dash-db&query=node&limit=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id":1,"uid":"abc","title":"Node Exporter","url":"/d/abc","type":"dash-db","tags":["infra"],"is_starred":false}]
                        """, MediaType.APPLICATION_JSON));

        List<Dashboard> results = client.searchDashboards("node", 0);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().uid()).isEqualTo("abc");
        assertThat(results.getFirst().title()).isEqualTo("Node Exporter");
        mockServer.verify();
    }

    @Test
    void searchDashboardsWithLimit() {
        mockServer.expect(requestTo("https://grafana.test/api/search?type=dash-db&query=&limit=10"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<Dashboard> results = client.searchDashboards(null, 10);
        assertThat(results).isEmpty();
        mockServer.verify();
    }

    @Test
    void getDashboard() {
        mockServer.expect(requestTo("https://grafana.test/api/dashboards/uid/xyz"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"dashboard":{"id":1,"title":"Test"},"meta":{"slug":"test"}}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = client.getDashboard("xyz");
        assertThat(result.get("dashboard").get("title").asText()).isEqualTo("Test");
        mockServer.verify();
    }

    @Test
    void listDatasources() {
        mockServer.expect(requestTo("https://grafana.test/api/datasources"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id":1,"uid":"prom1","name":"Prometheus","type":"prometheus","url":"http://prom:9090","is_default":true}]
                        """, MediaType.APPLICATION_JSON));

        List<Datasource> results = client.listDatasources();
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().name()).isEqualTo("Prometheus");
        assertThat(results.getFirst().isDefault()).isTrue();
        mockServer.verify();
    }

    @Test
    void listAnnotations() {
        mockServer.expect(requestTo("https://grafana.test/api/annotations?from=1000&to=2000"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id":1,"dashboard_id":5,"dashboard_uid":"abc","time":1000,"time_end":1500,"text":"deploy","tags":["deploy"]}]
                        """, MediaType.APPLICATION_JSON));

        List<Annotation> results = client.listAnnotations(1000, 2000, null, null);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().text()).isEqualTo("deploy");
        mockServer.verify();
    }

    @Test
    void listAnnotationsWithFilters() {
        mockServer.expect(requestTo(
                        "https://grafana.test/api/annotations?from=1000&to=2000&dashboardUID=abc&tags=deploy&tags=prod"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<Annotation> results = client.listAnnotations(1000, 2000, "abc", List.of("deploy", "prod"));
        assertThat(results).isEmpty();
        mockServer.verify();
    }

    @Test
    void createAnnotation() {
        mockServer.expect(requestTo("https://grafana.test/api/annotations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":42,"message":"Annotation added"}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = client.createAnnotation("dash1", 1000L, 2000L, "deployed", List.of("deploy"));
        assertThat(result.get("id").asInt()).isEqualTo(42);
        mockServer.verify();
    }

    @Test
    void createAnnotationMinimal() {
        mockServer.expect(requestTo("https://grafana.test/api/annotations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":43,"message":"Annotation added"}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = client.createAnnotation(null, 1000L, 0L, "note", null);
        assertThat(result.get("id").asInt()).isEqualTo(43);
        mockServer.verify();
    }

    @Test
    void queryPrometheus() {
        mockServer.expect(requestTo(
                        "https://grafana.test/api/datasources/proxy/1/api/v1/query_range?query=up&start=1000&end=2000&step=60s"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"matrix","result":[]}}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = client.queryPrometheus(1, "up", 1000, 2000, "60s");
        assertThat(result.get("status").asText()).isEqualTo("success");
        mockServer.verify();
    }

    @Test
    void queryPrometheusDefaultStep() {
        mockServer.expect(requestTo(
                        "https://grafana.test/api/datasources/proxy/2/api/v1/query_range?query=up&start=100&end=200&step=60s"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"matrix","result":[]}}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = client.queryPrometheus(2, "up", 100, 200, null);
        assertThat(result.get("status").asText()).isEqualTo("success");
        mockServer.verify();
    }

    @Test
    void errorResponseThrowsException() {
        mockServer.expect(requestTo("https://grafana.test/api/datasources"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError().body("{\"message\":\"internal error\"}"));

        assertThatThrownBy(() -> client.listDatasources())
                .isInstanceOf(GrafanaApiException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("internal error");
        mockServer.verify();
    }

    @Test
    void clampPageSizeDefaults() {
        assertThat(client.clampPageSize(0)).isEqualTo(50);
        assertThat(client.clampPageSize(-1)).isEqualTo(50);
    }

    @Test
    void clampPageSizeRespectsMax() {
        assertThat(client.clampPageSize(200)).isEqualTo(100);
        assertThat(client.clampPageSize(50)).isEqualTo(50);
    }
}
