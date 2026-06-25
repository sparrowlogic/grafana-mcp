package com.sparrowlogic.grafanamcp.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A Grafana dashboard search result.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Dashboard(int id, String uid, String title, String url,
        String type, List<String> tags, boolean isStarred) {
}
