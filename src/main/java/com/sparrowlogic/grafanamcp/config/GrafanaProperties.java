package com.sparrowlogic.grafanamcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits applied to the Grafana HTTP API client, bound from {@code grafana.*} properties.
 *
 * <p>No Grafana instance is configured here. Which Grafanas this server can reach — their base URLs
 * and tokens — comes solely from the user-editable file described by {@link EnvironmentStore}, and
 * every tool names the one it wants. Credentials therefore never live in the shipped jar or in the
 * launch environment, and adding an instance does not mean re-registering the MCP server.
 *
 * @param defaultPageSize page size used when a tool does not specify a limit
 * @param maxPageSize     hard upper bound on the page size a tool may request
 */
@ConfigurationProperties(prefix = "grafana")
public record GrafanaProperties(
        @DefaultValue("50") int defaultPageSize,
        @DefaultValue("100") int maxPageSize) {
}
