package com.sparrowlogic.grafanamcp.client;

import com.sparrowlogic.grafanamcp.config.GrafanaProperties;
import com.sparrowlogic.grafanamcp.domain.Annotation;
import com.sparrowlogic.grafanamcp.domain.Dashboard;
import com.sparrowlogic.grafanamcp.domain.Datasource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * HTTP client for the Grafana REST API, built on {@link RestClient}.
 */
@Component
public class GrafanaClient {

    private final RestClient rest;
    private final GrafanaProperties properties;

    public GrafanaClient(RestClient grafanaRestClient, GrafanaProperties properties) {
        this.rest = grafanaRestClient;
        this.properties = properties;
    }

    /** GET /api/search — search dashboards by query string. */
    public List<Dashboard> searchDashboards(String query, int limit) {
        return this.rest.get()
                .uri(b -> b.path("/api/search")
                        .queryParam("type", "dash-db")
                        .queryParam("query", query == null ? "" : query)
                        .queryParam("limit", this.clampPageSize(limit))
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(new ParameterizedTypeReference<List<Dashboard>>() { });
    }

    /** GET /api/dashboards/uid/{uid} — get full dashboard JSON by UID. */
    public JsonNode getDashboard(String uid) {
        return this.rest.get()
                .uri("/api/dashboards/uid/{uid}", uid)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(JsonNode.class);
    }

    /** GET /api/datasources — list all configured datasources. */
    public List<Datasource> listDatasources() {
        return this.rest.get()
                .uri("/api/datasources")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(new ParameterizedTypeReference<List<Datasource>>() { });
    }

    /** GET /api/annotations — list annotations in a time range. */
    public List<Annotation> listAnnotations(long fromMs, long toMs,
            String dashboardUid, List<String> tags) {
        return this.rest.get()
                .uri(b -> {
                    b.path("/api/annotations")
                            .queryParam("from", fromMs)
                            .queryParam("to", toMs);
                    if (dashboardUid != null && !dashboardUid.isBlank()) {
                        b.queryParam("dashboardUID", dashboardUid);
                    }
                    if (tags != null && !tags.isEmpty()) {
                        b.queryParam("tags", tags.toArray());
                    }
                    return b.build();
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(new ParameterizedTypeReference<List<Annotation>>() { });
    }

    /** POST /api/annotations — create an annotation. */
    public JsonNode createAnnotation(String dashboardUid, long timeMs, long timeEndMs,
            String text, List<String> tags) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("time", timeMs);
        body.put("text", text);
        if (timeEndMs > 0) {
            body.put("timeEnd", timeEndMs);
        }
        if (dashboardUid != null && !dashboardUid.isBlank()) {
            body.put("dashboardUID", dashboardUid);
        }
        if (tags != null && !tags.isEmpty()) {
            body.put("tags", tags);
        }
        return this.rest.post()
                .uri("/api/annotations")
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(JsonNode.class);
    }

    /**
     * GET /api/datasources/proxy/{id}/api/v1/query_range — execute a PromQL range query
     * via Grafana's datasource proxy.
     */
    public JsonNode queryPrometheus(int datasourceId, String promql,
            long startEpoch, long endEpoch, String step) {
        String resolvedStep = step == null || step.isBlank() ? "60s" : step;
        return this.rest.get()
                .uri("/api/datasources/proxy/{id}/api/v1/query_range?query={query}&start={start}&end={end}&step={step}",
                        datasourceId, promql, startEpoch, endEpoch, resolvedStep)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(JsonNode.class);
    }

    /** POST /api/short-urls — create a short URL for a Grafana path. */
    public String createShortUrl(String relativePath) {
        JsonNode response = this.rest.post()
                .uri("/api/short-urls")
                .body(Map.of("path", relativePath))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(JsonNode.class);
        String uid = response.get("uid").asText();
        return this.properties.baseUrl() + "/goto/" + uid;
    }

    int clampPageSize(int requested) {
        if (requested <= 0) {
            return this.properties.defaultPageSize();
        }
        return Math.min(requested, this.properties.maxPageSize());
    }

    private void raise(HttpRequest request, ClientHttpResponse response) throws IOException {
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        throw new GrafanaApiException(response.getStatusCode().value(), body);
    }
}
