package com.sparrowlogic.grafanamcp.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A Grafana datasource. Addressed by {@code uid}; {@code id} is the legacy numeric identifier,
 * kept as a {@code long} because current Grafana issues 64-bit snowflake ids.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Datasource(long id, String uid, String name, String type,
        String url, boolean isDefault) {
}
