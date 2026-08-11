package com.sparrowlogic.grafanamcp.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Binding for a Loki {@code /loki/api/v1/query_range} response whose {@code resultType} is
 * {@code streams} (a log query, as opposed to a metric query returning a matrix).
 *
 * <p>Unknown fields are ignored deliberately: the sibling {@code stats} object Loki attaches to
 * every response is several hundred fields of query-engine telemetry and is one of the larger
 * sources of token bloat in a naive integration. Dropping it at binding time means it can never
 * reach an MCP response.
 *
 * @param status {@code "success"} on a well-formed response
 * @param data   the result payload
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LokiQueryResponse(String status, Data data) {

    /**
     * The {@code data} member of a query response.
     *
     * @param resultType {@code "streams"} for log queries
     * @param result     one entry per label-set (stream) matched
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(String resultType, List<Stream> result) {
    }

    /**
     * One stream: a distinct label set plus the log entries recorded against it, oldest first.
     *
     * <p>{@code values} is kept as raw {@link JsonNode} arrays rather than {@code List<String>}
     * because Loki appends a third, non-string element (structured metadata) to each entry on
     * some versions/configurations; binding to strings would fail there. Element 0 is the
     * nanosecond timestamp as a string, element 1 the log line.
     *
     * @param stream the stream's labels
     * @param values one array per entry: {@code [timestampNs, line]}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stream(Map<String, String> stream, List<JsonNode> values) {
    }
}
