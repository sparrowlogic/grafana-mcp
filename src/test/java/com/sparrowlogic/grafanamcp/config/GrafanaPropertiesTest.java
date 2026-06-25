package com.sparrowlogic.grafanamcp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrafanaPropertiesTest {

    @Test
    void defaultValues() {
        GrafanaProperties props = new GrafanaProperties("token", null, 0, 0);
        assertThat(props.token()).isEqualTo("token");
    }

    @Test
    void allValuesSet() {
        GrafanaProperties props = new GrafanaProperties(
                "my-token", "https://grafana.example.com", 25, 50);
        assertThat(props.token()).isEqualTo("my-token");
        assertThat(props.baseUrl()).isEqualTo("https://grafana.example.com");
        assertThat(props.defaultPageSize()).isEqualTo(25);
        assertThat(props.maxPageSize()).isEqualTo(50);
    }
}
