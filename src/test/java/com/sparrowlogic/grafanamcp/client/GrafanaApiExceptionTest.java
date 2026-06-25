package com.sparrowlogic.grafanamcp.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrafanaApiExceptionTest {

    @Test
    void messageIncludesStatusAndBody() {
        GrafanaApiException ex = new GrafanaApiException(404, "not found");
        assertThat(ex.getMessage()).contains("404");
        assertThat(ex.getMessage()).contains("not found");
        assertThat(ex.getStatus()).isEqualTo(404);
    }

    @Test
    void messageHandlesNullBody() {
        GrafanaApiException ex = new GrafanaApiException(500, null);
        assertThat(ex.getMessage()).contains("500");
        assertThat(ex.getMessage()).doesNotContain("null");
    }

    @Test
    void messageHandlesBlankBody() {
        GrafanaApiException ex = new GrafanaApiException(403, "  ");
        assertThat(ex.getMessage()).contains("403");
    }
}
