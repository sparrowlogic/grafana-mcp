package com.sparrowlogic.grafanamcp.client;

import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments.Target;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds Grafana Explore links for a query, so an answer can point at the live view rather than
 * being the only place the data exists.
 *
 * <p>Grafana encodes Explore state as a JSON document in the {@code left} query parameter. The
 * document is assembled with Jackson rather than string concatenation because LogQL and PromQL
 * expressions contain quotes and backslashes that must be escaped correctly, then percent-encoded
 * strictly (see {@link PercentEncoding}) so no brace or quote reaches the URL literally. Grafana's
 * short-URL API is used when it will accept the path, with the (long) direct link as a fallback.
 */
@Component
public class ExploreLinks {

    private final GrafanaClient client;

    public ExploreLinks(GrafanaClient client) {
        this.client = client;
    }

    /**
     * A link that opens one query, against one datasource, over one time range in Explore.
     *
     * <p>{@code queryFields} carries the datasource's own query model, because each one differs:
     * Loki wants {@code expr} plus {@code queryType=range}, Prometheus wants {@code expr} plus a
     * boolean {@code range}, Tempo wants {@code query} plus {@code queryType=traceql}. Only the
     * envelope — datasource, queries array, {@code refId}, time range, encoding, short URL — is
     * shared, and it lives here so a new signal type needs no change to this class.
     *
     * @param target         the Grafana instance the link should point at
     * @param datasourceUid  UID of the datasource to query
     * @param datasourceType Grafana datasource type, e.g. {@code loki}, {@code prometheus}
     * @param queryFields    the datasource's query model; String and Boolean values are supported
     * @param startMs        range start in epoch milliseconds
     * @param endMs          range end in epoch milliseconds
     */
    public String explore(Target target, String datasourceUid, String datasourceType,
            Map<String, Object> queryFields, long startMs, long endMs) {
        String state = this.state(datasourceUid, datasourceType, queryFields, startMs, endMs);
        String path = "explore?orgId=1&left=" + PercentEncoding.encode(state);
        try {
            return this.client.createShortUrl(target, path);
        } catch (GrafanaApiException ex) {
            return target.baseUrl() + "/" + path;
        }
    }

    /** The Explore pane state Grafana expects in the {@code left} parameter. */
    private String state(String datasourceUid, String datasourceType,
            Map<String, Object> queryFields, long startMs, long endMs) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode datasource = nodes.objectNode()
                .put("uid", datasourceUid)
                .put("type", datasourceType);
        ObjectNode query = nodes.objectNode().put("refId", "A");
        queryFields.forEach((name, value) -> {
            if (value instanceof Boolean flag) {
                query.put(name, flag);
            } else {
                query.put(name, String.valueOf(value));
            }
        });
        ArrayNode queries = nodes.arrayNode().add(query);
        ObjectNode range = nodes.objectNode()
                .put("from", Long.toString(startMs))
                .put("to", Long.toString(endMs));
        ObjectNode state = nodes.objectNode();
        state.set("datasource", datasource);
        state.set("queries", queries);
        state.set("range", range);
        return state.toString();
    }

}
