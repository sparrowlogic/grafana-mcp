package com.sparrowlogic.grafanamcp.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A Grafana dashboard search result.
 *
 * <p>{@code id} is a {@code long}: current Grafana issues 64-bit snowflake ids
 * (e.g. 1223437903945728), and binding one into an {@code int} fails the entire response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Dashboard(long id, String uid, String title, String url,
        String type, List<String> tags, boolean isStarred) {
}
