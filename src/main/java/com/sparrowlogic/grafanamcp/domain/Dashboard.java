package com.sparrowlogic.grafanamcp.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A Grafana dashboard search result.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Dashboard(int id, String uid, String title, String url,
        String type, List<String> tags, boolean isStarred) {
}
