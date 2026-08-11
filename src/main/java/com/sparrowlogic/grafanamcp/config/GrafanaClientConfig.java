package com.sparrowlogic.grafanamcp.config;

import com.sparrowlogic.grafanamcp.tools.AnnotationTools;
import com.sparrowlogic.grafanamcp.tools.DashboardTools;
import com.sparrowlogic.grafanamcp.tools.EnvironmentTools;
import com.sparrowlogic.grafanamcp.tools.LokiTools;
import com.sparrowlogic.grafanamcp.tools.MetricsTools;
import com.sparrowlogic.grafanamcp.tools.NodeMetricsTools;
import com.sparrowlogic.grafanamcp.tools.TempoTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the configuration properties and registers the MCP tools.
 *
 * <p>HTTP clients are not created here: there is one per configured Grafana environment, built by
 * {@code GrafanaEnvironments}, because each carries its own base URL and token.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GrafanaProperties.class, PrometheusProperties.class,
                                LokiProperties.class, TempoProperties.class})
public class GrafanaClientConfig {

    @Bean
    ToolCallbackProvider grafanaToolCallbackProvider(DashboardTools dashboardTools,
            AnnotationTools annotationTools, MetricsTools metricsTools,
            NodeMetricsTools nodeMetricsTools, LokiTools lokiTools, TempoTools tempoTools,
            EnvironmentTools environmentTools) {
        // Hand-maintained allowlist: a @Component absent from here registers zero tools, silently.
        return MethodToolCallbackProvider.builder()
                .toolObjects(dashboardTools, annotationTools, metricsTools, nodeMetricsTools,
                        lokiTools, tempoTools, environmentTools)
                .build();
    }
}
