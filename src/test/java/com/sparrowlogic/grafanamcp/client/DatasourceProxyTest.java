package com.sparrowlogic.grafanamcp.client;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DatasourceProxyTest {

    private static final String DATASOURCES = """
            [{"id":1,"uid":"prom-uid","name":"Prometheus","type":"prometheus","isDefault":true},
             {"id":2,"uid":"loki-uid","name":"Loki","type":"loki","isDefault":false},
             {"id":3,"uid":"tempo","name":"Tempo","type":"tempo","isDefault":false}]
            """;

    private MockRestServiceServer mockServer;
    private DatasourceProxy proxy;
    private GrafanaEnvironments.Target target;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(builder).build();
        this.proxy = new DatasourceProxy();
        this.target = new GrafanaEnvironments.Target("test", "https://grafana.test", builder.build());
    }

    private void expectDatasources() {
        this.mockServer.expect(requestTo("https://grafana.test/api/datasources"))
                .andRespond(withSuccess(DATASOURCES, MediaType.APPLICATION_JSON));
    }

    @Test
    void proxyUriUsesTheUidPathAndEncodesQueryValues() {
        URI uri = this.proxy.uri(this.target, "loki-uid", "/loki/api/v1/query_range",
                DatasourceProxy.params("query", "{env=~\".+\"}", "limit", "10"));

        // Braces and quotes encoded so they are not read as URI templates, and '+' encoded so a Go
        // server does not decode it as a space.
        assertThat(uri).hasToString(
                "https://grafana.test/api/datasources/proxy/uid/loki-uid/loki/api/v1/query_range"
                        + "?query=%7Benv%3D~%22.%2B%22%7D&limit=10");
    }

    @Test
    void anExplicitUidIsUsedAsIs() {
        assertThat(this.proxy.resolveUid(this.target, "some-uid", "loki")).isEqualTo("some-uid");
        this.mockServer.verify();
    }

    @Test
    void aBlankUidDiscoversByTypeAndCachesTheAnswer() {
        this.expectDatasources();

        assertThat(this.proxy.resolveUid(this.target, null, "loki")).isEqualTo("loki-uid");
        assertThat(this.proxy.resolveUid(this.target, "  ", "loki")).isEqualTo("loki-uid");
        assertThat(this.proxy.resolveUid(this.target, "", "LOKI")).isEqualTo("loki-uid");
        this.mockServer.verify();
    }

    @Test
    void discoveryIsPerTypeSoEachSignalGetsItsOwn() {
        this.expectDatasources();
        this.expectDatasources();

        assertThat(this.proxy.resolveUid(this.target, null, "prometheus")).isEqualTo("prom-uid");
        assertThat(this.proxy.resolveUid(this.target, null, "tempo")).isEqualTo("tempo");
        this.mockServer.verify();
    }

    @Test
    void aLegacyNumericIdIsTranslatedRatherThanFailing() {
        this.expectDatasources();

        // Callers written against the old int-typed tools keep working.
        assertThat(this.proxy.resolveUid(this.target, "1", "prometheus")).isEqualTo("prom-uid");
    }

    @Test
    void anUnknownNumericIdSaysWhatToDoInstead() {
        this.expectDatasources();

        assertThatThrownBy(() -> this.proxy.resolveUid(this.target, "99", "prometheus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No datasource has id 99")
                .hasMessageContaining("list_datasources");
    }

    @Test
    void aMissingTypeSaysWhatToDoInstead() {
        this.expectDatasources();

        assertThatThrownBy(() -> this.proxy.resolveUid(this.target, null, "elasticsearch"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No datasource of type 'elasticsearch'");
    }

    @Test
    void discoveryIsCachedPerEnvironmentNotGlobally() {
        RestClient.Builder other = RestClient.builder();
        MockRestServiceServer otherServer = MockRestServiceServer.bindTo(other).build();
        GrafanaEnvironments.Target second = new GrafanaEnvironments.Target("other",
                "https://other.test", other.build());
        this.expectDatasources();
        otherServer.expect(requestTo("https://other.test/api/datasources"))
                .andRespond(withSuccess("""
                        [{"id":9,"uid":"other-loki","name":"Loki","type":"loki","isDefault":false}]
                        """, MediaType.APPLICATION_JSON));

        // The same type resolves to a different UID per Grafana, so one shared cache entry would
        // send the second environment's query to the first environment's datasource.
        assertThat(this.proxy.resolveUid(this.target, null, "loki")).isEqualTo("loki-uid");
        assertThat(this.proxy.resolveUid(second, null, "loki")).isEqualTo("other-loki");
        this.mockServer.verify();
        otherServer.verify();
    }

    @Test
    void getMapsErrorStatusesToGrafanaApiException() {
        this.mockServer.expect(requestTo(
                        "https://grafana.test/api/datasources/proxy/uid/loki-uid/loki/api/v1/labels"))
                .andRespond(withServerError().body("boom"));

        assertThatThrownBy(() -> this.proxy.get(this.target,
                this.proxy.uri(this.target, "loki-uid", "/loki/api/v1/labels", Map.of()), JsonNode.class))
                .isInstanceOf(GrafanaApiException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void datasourcesAreReturnedWithUidAndType() {
        this.expectDatasources();

        assertThat(this.proxy.datasources(this.target)).hasSize(3)
                .anySatisfy(datasource -> {
                    assertThat(datasource.uid()).isEqualTo("loki-uid");
                    assertThat(datasource.type()).isEqualTo("loki");
                });
    }

}
