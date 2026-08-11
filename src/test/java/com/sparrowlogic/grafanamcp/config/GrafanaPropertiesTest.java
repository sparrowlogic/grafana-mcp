package com.sparrowlogic.grafanamcp.config;

import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrafanaPropertiesTest {

    @Test
    void carriesThePageSizeLimits() {
        GrafanaProperties props = new GrafanaProperties(25, 50);

        assertThat(props.defaultPageSize()).isEqualTo(25);
        assertThat(props.maxPageSize()).isEqualTo(50);
    }

    @Test
    void holdsNoCredentialAndNoGrafanaInstance() {
        // Base URLs and tokens belong to EnvironmentStore's file alone. If a component reappears
        // here, credentials are back in the shipped jar and the launch environment.
        assertThat(GrafanaProperties.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactlyInAnyOrder("defaultPageSize", "maxPageSize");
    }
}
