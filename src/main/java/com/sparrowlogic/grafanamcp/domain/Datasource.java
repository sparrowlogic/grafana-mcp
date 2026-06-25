package com.sparrowlogic.grafanamcp.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A Grafana datasource. The {@code id} is needed for {@code query_prometheus}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Datasource(int id, String uid, String name, String type,
        String url, boolean isDefault) {
}
