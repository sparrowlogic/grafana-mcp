package com.sparrowlogic.grafanamcp.client;

import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments.Target;
import com.sparrowlogic.grafanamcp.domain.Datasource;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Reaches a datasource's own API through Grafana's datasource proxy. Every signal type — Prometheus,
 * Loki, and anything added later — is addressed the same way, so this is the one place that knows
 * how to build such a request.
 *
 * <p>Three things here are load-bearing, each learned the hard way:
 *
 * <ul>
 *   <li><b>The proxy path is UID-based</b> ({@code /api/datasources/proxy/uid/{uid}/…}). The legacy
 *       numeric form ({@code /api/datasources/proxy/{id}/…}) is gone from current Grafana and
 *       returns 404 for every datasource.</li>
 *   <li><b>Query values are percent-encoded strictly</b> via {@link PercentEncoding}, then built
 *       with {@code build(true)}. Query languages are full of braces, quotes and pipes that URI
 *       templates would try to expand, and of {@code +} characters that a Go server would otherwise
 *       read as spaces.</li>
 *   <li><b>A datasource can be named by type.</b> Callers may pass a UID, or nothing at all and let
 *       the first datasource of the wanted type be discovered and remembered — per environment,
 *       since the same datasource has a different UID in each Grafana.</li>
 * </ul>
 */
@Component
public class DatasourceProxy {

    /** "environment|type" to discovered UID, cached for the life of the process. */
    private final Map<String, String> uidByType = new ConcurrentHashMap<>();

    /** GET /api/datasources — every datasource configured in that Grafana. */
    public List<Datasource> datasources(Target target) {
        return target.rest().get()
                .uri(URI.create(target.baseUrl() + "/api/datasources"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(new ParameterizedTypeReference<List<Datasource>>() { });
    }

    /**
     * Resolves which datasource to talk to.
     *
     * <p>{@code requested} may be a UID, blank (discover the first datasource of
     * {@code datasourceType}), or — for compatibility with callers written against the older tools —
     * a numeric datasource id, which is translated to its UID rather than failing.
     *
     * @throws IllegalStateException    if no datasource of that type exists
     * @throws IllegalArgumentException if a numeric id matches no datasource
     */
    public String resolveUid(Target target, String requested, String datasourceType) {
        String value = requested == null ? "" : requested.trim();
        if (value.isEmpty()) {
            return this.discover(target, datasourceType);
        }
        return value.matches("\\d+") ? this.uidForLegacyId(target, value) : value;
    }

    /**
     * Builds an absolute, fully-encoded proxy URI for a path on the datasource's own API.
     *
     * @param uid            datasource UID
     * @param datasourcePath path within the datasource API, e.g. {@code /api/v1/query_range}
     * @param params         query parameters, encoded here (pass raw values)
     */
    public URI uri(Target target, String uid, String datasourcePath, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(target.baseUrl())
                .path("/api/datasources/proxy/uid/"
                        + UriUtils.encodePathSegment(uid, StandardCharsets.UTF_8) + datasourcePath);
        params.forEach((name, value) -> builder.queryParam(name, PercentEncoding.encode(value)));
        return builder.build(true).toUri();
    }

    /** GETs a proxied URI, mapping any error status to {@link GrafanaApiException}. */
    public <T> T get(Target target, URI uri, Class<T> responseType) {
        return this.get(target, uri, responseType, MediaType.APPLICATION_JSON);
    }

    /**
     * GETs a proxied URI asking for a specific representation — Tempo, for instance, serves a much
     * leaner trace document for {@code application/vnd.grafana.llm}.
     *
     * <p>Uses {@code accept(...)}, which replaces the client's default {@code Accept} header for
     * this request, rather than {@code header(ACCEPT, ...)}, which would append a second value and
     * let the server pick.
     */
    public <T> T get(Target target, URI uri, Class<T> responseType, MediaType accept) {
        return target.rest().get()
                .uri(uri)
                .accept(accept)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(responseType);
    }

    /** Convenience for the near-universal {@code start}/{@code end} pair, in a fixed order. */
    public static Map<String, String> params(String... nameValuePairs) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            params.put(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return params;
    }

    /**
     * Discovers the datasource of a type, remembering it per environment — the same type has a
     * different UID in each Grafana, so one shared cache entry would send a query to the wrong one.
     */
    private String discover(Target target, String datasourceType) {
        String type = datasourceType.toLowerCase(Locale.ROOT);
        return this.uidByType.computeIfAbsent(target.name() + '|' + type,
                key -> this.datasources(target).stream()
                        .filter(datasource -> type.equalsIgnoreCase(datasource.type()))
                        .map(Datasource::uid)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No datasource of type '" + type
                                + "' is configured in Grafana environment '" + target.name()
                                + "'. Pass a datasourceUid explicitly, or check list_datasources.")));
    }

    /** Translates a legacy numeric datasource id to its UID. */
    private String uidForLegacyId(Target target, String id) {
        int numericId = Integer.parseInt(id);
        return this.datasources(target).stream()
                .filter(datasource -> datasource.id() == numericId)
                .map(Datasource::uid)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No datasource has id " + numericId
                        + ". Datasources are addressed by UID now — see list_datasources."));
    }

    private void raise(HttpRequest request, ClientHttpResponse response) throws IOException {
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        throw new GrafanaApiException(response.getStatusCode().value(), body);
    }
}
