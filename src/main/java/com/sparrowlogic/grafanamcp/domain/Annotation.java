package com.sparrowlogic.grafanamcp.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A Grafana annotation (point-in-time or range).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Annotation(int id, int dashboardId, String dashboardUID,
        long time, long timeEnd, String text, List<String> tags) {
}
