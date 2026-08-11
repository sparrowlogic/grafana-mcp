package com.sparrowlogic.grafanamcp.client;

import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments.Target;
import com.sparrowlogic.grafanamcp.config.TempoProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;

/**
 * Read-only client for Tempo's query API, reached through Grafana's datasource proxy (the only route
 * to Tempo on this deployment — the backend has no separately reachable hostname).
 *
 * <p>Traces are requested in Tempo's <b>LLM response format</b> when it is available: the same trace
 * is materially smaller than OTLP — measured 30 337 bytes against 41 036 — because attributes come
 * back as flat key/value pairs instead of {@code {"key":…,"value":{"stringValue":…}}} envelopes, and
 * span durations arrive precomputed. It is a Tempo 2.x convenience, so a rejection falls back to
 * OTLP rather than failing; {@code ParsedTrace} reads either.
 *
 * <p>Addressing, encoding and error mapping belong to {@link DatasourceProxy}.
 */
@Component
public class TempoClient {

    /** Tempo's compact, LLM-oriented trace representation. */
    private static final MediaType LLM_FORMAT = MediaType.valueOf("application/vnd.grafana.llm");

    private final DatasourceProxy proxy;
    private final TempoProperties properties;

    public TempoClient(DatasourceProxy proxy, TempoProperties properties) {
        this.proxy = proxy;
        this.properties = properties;
    }

    /**
     * Resolves which Tempo datasource to query: an explicit UID, then
     * {@code grafana.tempo.datasource-uid}, then the first datasource of type {@code tempo}.
     */
    public String resolveDatasourceUid(Target target, String requestedUid) {
        String requested = requestedUid == null || requestedUid.isBlank()
                ? this.properties.datasourceUid()
                : requestedUid;
        return this.proxy.resolveUid(target, requested, "tempo");
    }

    /**
     * GET {@code /api/search} — TraceQL search. Returns Tempo's hit list verbatim for projection by
     * the caller.
     *
     * @param traceql    TraceQL query, e.g. <code>{ resource.service.name = "web" &amp;&amp; duration &gt; 2s }</code>
     * @param startSec   window start, epoch seconds
     * @param endSec     window end, epoch seconds
     * @param limit      maximum traces to return
     */
    public JsonNode searchTraces(Target target, String uid, String traceql, long startSec,
            long endSec, int limit) {
        Map<String, String> params = DatasourceProxy.params(
                "q", traceql,
                "start", Long.toString(startSec),
                "end", Long.toString(endSec),
                "limit", Integer.toString(limit),
                "spss", Integer.toString(Math.max(1, this.properties.spansPerHit())));
        return this.proxy.get(target, this.proxy.uri(target, uid, "/api/search", params),
                JsonNode.class);
    }

    /**
     * GET {@code /api/v2/traces/{traceId}} — one trace, in the LLM format when Tempo offers it.
     *
     * <p>The window matters: Tempo only searches the blocks covering {@code start}/{@code end}, so a
     * trace outside the requested window comes back empty rather than missing.
     */
    public JsonNode trace(Target target, String uid, String traceId, long startSec, long endSec) {
        Map<String, String> params = DatasourceProxy.params(
                "start", Long.toString(startSec),
                "end", Long.toString(endSec));
        URI uri = this.proxy.uri(target, uid, "/api/v2/traces/"
                + UriUtils.encodePathSegment(traceId, StandardCharsets.UTF_8), params);
        try {
            return this.proxy.get(target, uri, JsonNode.class, LLM_FORMAT);
        } catch (GrafanaApiException ex) {
            // A Tempo that does not know the LLM format answers 406/415; OTLP is always available.
            return this.proxy.get(target, uri, JsonNode.class);
        }
    }

    /**
     * GET {@code /api/v2/search/tags} — attribute names, grouped by scope
     * ({@code resource}, {@code span}, {@code intrinsic}, …). The v2 form is scoped, which is what
     * makes the result readable; a Tempo too old for it falls back to the flat v1 list.
     */
    public JsonNode tags(Target target, String uid, String scope, long startSec, long endSec) {
        Map<String, String> params = DatasourceProxy.params(
                "start", Long.toString(startSec),
                "end", Long.toString(endSec));
        if (scope != null && !scope.isBlank()) {
            params.put("scope", scope.trim());
        }
        try {
            return this.proxy.get(target, this.proxy.uri(target, uid, "/api/v2/search/tags", params),
                    JsonNode.class);
        } catch (GrafanaApiException ex) {
            return this.proxy.get(target, this.proxy.uri(target, uid, "/api/search/tags", params),
                    JsonNode.class);
        }
    }

    /** GET {@code /api/v2/search/tag/{tag}/values} — the values of one trace attribute. */
    public JsonNode tagValues(Target target, String uid, String tag, long startSec, long endSec) {
        Map<String, String> params = DatasourceProxy.params(
                "start", Long.toString(startSec),
                "end", Long.toString(endSec));
        String encoded = UriUtils.encodePathSegment(tag, StandardCharsets.UTF_8);
        try {
            return this.proxy.get(target, this.proxy.uri(target, uid,
                    "/api/v2/search/tag/" + encoded + "/values", params), JsonNode.class);
        } catch (GrafanaApiException ex) {
            return this.proxy.get(target, this.proxy.uri(target, uid,
                    "/api/search/tag/" + encoded + "/values", params), JsonNode.class);
        }
    }

    /**
     * GET {@code /api/metrics/query_range} — TraceQL metrics, e.g.
     * <code>{ resource.service.name = "web" } | rate()</code>. Returns Tempo's series response
     * verbatim.
     */
    public JsonNode metricsQueryRange(Target target, String uid, String traceql, long startSec,
            long endSec, String step) {
        Map<String, String> params = DatasourceProxy.params(
                "q", traceql,
                "start", Long.toString(startSec),
                "end", Long.toString(endSec),
                "step", step);
        return this.proxy.get(target, this.proxy.uri(target, uid, "/api/metrics/query_range", params),
                JsonNode.class);
    }
}
