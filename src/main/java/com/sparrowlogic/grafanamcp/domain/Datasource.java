package com.sparrowlogic.grafanamcp.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A Grafana datasource. The {@code id} is needed for {@code query_prometheus}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Datasource(int id, String uid, String name, String type,
        String url, boolean isDefault) {
}
