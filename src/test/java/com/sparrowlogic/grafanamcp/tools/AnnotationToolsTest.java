package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.domain.Annotation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnotationToolsTest {

    @Mock
    GrafanaClient client;

    @InjectMocks
    AnnotationTools tools;

    @Test
    void listAnnotationsWithIso8601Times() {
        long expectedFrom = java.time.Instant.parse("2026-06-25T00:00:00Z").toEpochMilli();
        long expectedTo = java.time.Instant.parse("2026-06-25T12:00:00Z").toEpochMilli();
        List<Annotation> expected = List.of(
                new Annotation(1, 5, "uid", 1000L, 2000L, "test", List.of()));
        when(client.listAnnotations(eq(expectedFrom), eq(expectedTo), any(), any())).thenReturn(expected);

        List<Annotation> result = tools.listAnnotations(
                "2026-06-25T00:00:00Z", "2026-06-25T12:00:00Z", null, null);
        assertThat(result).isEqualTo(expected);
        verify(client).listAnnotations(expectedFrom, expectedTo, null, List.of());
    }

    @Test
    void listAnnotationsWithEpochMs() {
        when(client.listAnnotations(1000L, 2000L, "dash1", List.of("deploy")))
                .thenReturn(List.of());

        List<Annotation> result = tools.listAnnotations("1000", "2000", "dash1", "deploy");
        assertThat(result).isEmpty();
        verify(client).listAnnotations(1000L, 2000L, "dash1", List.of("deploy"));
    }

    @Test
    void listAnnotationsWithMultipleTags() {
        when(client.listAnnotations(anyLong(), anyLong(), any(), eq(List.of("a", "b"))))
                .thenReturn(List.of());

        tools.listAnnotations("1000", "2000", null, "a, b");
        verify(client).listAnnotations(1000L, 2000L, null, List.of("a", "b"));
    }

    @Test
    void createAnnotationWithAllParams() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", 1);
        when(client.createAnnotation(eq("dash1"), anyLong(), anyLong(), eq("deployed"), eq(List.of("deploy"))))
                .thenReturn(node);

        var result = tools.createAnnotation("deployed", "2026-06-25T00:00:00Z",
                "2026-06-25T01:00:00Z", "dash1", "deploy");
        assertThat(result.get("id").asInt()).isEqualTo(1);
    }

    @Test
    void createAnnotationMinimal() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", 2);
        when(client.createAnnotation(any(), anyLong(), eq(0L), eq("note"), eq(List.of())))
                .thenReturn(node);

        var result = tools.createAnnotation("note", "1000", null, null, null);
        assertThat(result.get("id").asInt()).isEqualTo(2);
    }

    @Test
    void parseTimeMsHandlesIso8601() {
        long ms = AnnotationTools.parseTimeMs("2026-01-01T00:00:00Z");
        long expected = java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        assertThat(ms).isEqualTo(expected);
    }

    @Test
    void parseTimeMsHandlesEpoch() {
        long ms = AnnotationTools.parseTimeMs("1234567890");
        assertThat(ms).isEqualTo(1234567890L);
    }

    @Test
    void parseTimeMsHandlesNull() {
        long ms = AnnotationTools.parseTimeMs(null);
        assertThat(ms).isGreaterThan(0);
    }

    @Test
    void parseTimeMsHandlesBlank() {
        long ms = AnnotationTools.parseTimeMs("  ");
        assertThat(ms).isGreaterThan(0);
    }
}
