package com.sparrowlogic.grafanamcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@link RestClient} used to talk to the Grafana HTTP API, pre-configured
 * with the base URL and auth header every request requires.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GrafanaProperties.class)
public class GrafanaClientConfig {

    @Bean
    RestClient grafanaRestClient(GrafanaProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
