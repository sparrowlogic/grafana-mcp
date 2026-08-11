package com.sparrowlogic.grafanamcp.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A Grafana annotation (point-in-time or range).
 *
 * <p>Ids are {@code long} because current Grafana issues 64-bit snowflake ids.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Annotation(long id, long dashboardId, String dashboardUID,
        long time, long timeEnd, String text, List<String> tags) {
}
