package com.sparrowlogic.grafanamcp.client;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExploreLinksTest {

    private static final String LOGQL = "{app=\"a\"} |~ \"back\\\\slash\" |= `tick`";

    private final GrafanaClient client = mock(GrafanaClient.class);
    private final ExploreLinks links = new ExploreLinks(this.client);
    private final GrafanaEnvironments.Target target = new GrafanaEnvironments.Target("test",
            "https://grafana.test", null);

    @Test
    void shortUrlIsPreferredAndCarriesTheQueryUnmangled() {
        when(this.client.createShortUrl(any(), anyString())).thenReturn("https://grafana.test/goto/abc");

        String url = this.links.explore(this.target, "loki-uid", "loki",
                Map.of("expr", LOGQL, "queryType", "range"), 1000L, 2000L);

        assertThat(url).isEqualTo("https://grafana.test/goto/abc");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(this.client).createShortUrl(eq(this.target), path.capture());
        // Braces and quotes must be percent-encoded, never literal, in the Explore path.
        assertThat(path.getValue()).startsWith("explore?orgId=1&left=").doesNotContain("{", "\"");

        JsonNode state = decodeState(path.getValue());
        assertThat(state.path("datasource").path("uid").asString()).isEqualTo("loki-uid");
        assertThat(state.path("datasource").path("type").asString()).isEqualTo("loki");
        assertThat(state.path("queries").path(0).path("expr").asString()).isEqualTo(LOGQL);
        assertThat(state.path("queries").path(0).path("queryType").asString()).isEqualTo("range");
        assertThat(state.path("range").path("from").asString()).isEqualTo("1000");
        assertThat(state.path("range").path("to").asString()).isEqualTo("2000");
    }

    @Test
    void prometheusQueryFieldsAreCarriedWithTheirOwnTypes() {
        when(this.client.createShortUrl(any(), anyString())).thenReturn("https://grafana.test/goto/abc");

        this.links.explore(this.target, "prom-uid", "prometheus", Map.of("expr", "up", "range", true),
                1000L, 2000L);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(this.client).createShortUrl(eq(this.target), path.capture());
        JsonNode query = decodeState(path.getValue()).path("queries").path(0);
        assertThat(query.path("expr").asString()).isEqualTo("up");
        // Prometheus uses a boolean flag where Loki uses queryType — the value type must survive.
        assertThat(query.path("range").isBoolean()).isTrue();
        assertThat(query.path("range").asBoolean()).isTrue();
        assertThat(query.path("refId").asString()).isEqualTo("A");
    }

    @Test
    void aRejectedShortUrlFallsBackToTheDirectLink() {
        when(this.client.createShortUrl(any(), anyString()))
                .thenThrow(new GrafanaApiException(400, "bad path"));

        String url = this.links.explore(this.target, "loki-uid", "loki",
                Map.of("expr", LOGQL, "queryType", "range"), 1000L, 2000L);

        assertThat(url).startsWith("https://grafana.test/explore?orgId=1&left=");
        assertThat(decodeState(url.substring(url.indexOf("left=") + 5))
                .path("queries").path(0).path("expr").asString()).isEqualTo(LOGQL);
    }

    /** Decodes the {@code left=} payload back into the Explore state document. */
    private static JsonNode decodeState(String pathOrValue) {
        String encoded = pathOrValue.contains("left=")
                ? pathOrValue.substring(pathOrValue.indexOf("left=") + 5)
                : pathOrValue;
        return JsonMapper.builder().build()
                .readTree(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
    }
}
