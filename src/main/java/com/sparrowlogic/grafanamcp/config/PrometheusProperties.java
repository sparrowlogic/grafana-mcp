package com.sparrowlogic.grafanamcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds on a Prometheus range-query response, bound from {@code grafana.prometheus.*}.
 *
 * <p>These exist because an unbounded range query is a context-window hazard: {@code up} over ten
 * minutes on this deployment returns 748 series and roughly 330 000 characters of JSON. A range
 * query's cost is series × points, so both are capped, and points are omitted altogether unless
 * asked for.
 *
 * @param datasourceUid      UID of the Prometheus datasource; blank means discover by type
 * @param maxSeries          series kept in a response, highest peak first
 * @param maxPointsPerSeries points kept per series when samples are requested; a longer range is
 *                           bucketed down to this many, keeping each bucket's maximum so spikes
 *                           survive
 */
@ConfigurationProperties(prefix = "grafana.prometheus")
public record PrometheusProperties(
        String datasourceUid,
        @DefaultValue("25") int maxSeries,
        @DefaultValue("60") int maxPointsPerSeries) {
}
