package com.sparrowlogic.grafanamcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the Grafana HTTP API client, bound from {@code grafana.*} properties.
 * Both {@code token} and {@code baseUrl} are required — the application will fail to start
 * if either GRAFANA_TOKEN or GRAFANA_URL is not set.
 *
 * @param token           service account token sent as {@code Authorization: Bearer <token>}
 * @param baseUrl         Grafana instance base URL (required)
 * @param defaultPageSize page size used when a tool does not specify a limit
 * @param maxPageSize     hard upper bound on the page size a tool may request
 */
@ConfigurationProperties(prefix = "grafana")
public record GrafanaProperties(
        String token,
        String baseUrl,
        @DefaultValue("50") int defaultPageSize,
        @DefaultValue("100") int maxPageSize) {
}
