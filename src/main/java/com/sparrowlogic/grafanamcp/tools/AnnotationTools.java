package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.domain.Annotation;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * MCP tools for Grafana annotation operations.
 */
@Component
public class AnnotationTools {

    private final GrafanaClient client;

    public AnnotationTools(GrafanaClient client) {
        this.client = client;
    }

    @Tool(name = "list_annotations",
            description = "List Grafana annotations within a time range. Optionally filter "
                    + "by dashboard UID or tags. Times accept ISO-8601 (e.g. "
                    + "2026-06-25T00:00:00Z) or epoch milliseconds.")
    public List<Annotation> listAnnotations(
            @ToolParam(description = "Start of time range (ISO-8601 or epoch ms).")
            String from,
            @ToolParam(description = "End of time range (ISO-8601 or epoch ms).")
            String to,
            @ToolParam(description = "Dashboard UID to filter by. Optional.",
                    required = false)
            String dashboardUid,
            @ToolParam(description = "Comma-separated tags to filter by. Optional.",
                    required = false)
            String tags) {
        long fromMs = parseTimeMs(from);
        long toMs = parseTimeMs(to);
        List<String> tagList = parseTags(tags);
        return this.client.listAnnotations(fromMs, toMs, dashboardUid, tagList);
    }

    @Tool(name = "create_annotation",
            description = "Create a Grafana annotation. Can be a point-in-time event or a "
                    + "time range (if timeEnd is provided). Useful for marking deploys, "
                    + "incidents, or other events on dashboards.")
    public JsonNode createAnnotation(
            @ToolParam(description = "Annotation text/description.")
            String text,
            @ToolParam(description = "Start time (ISO-8601 or epoch ms).")
            String time,
            @ToolParam(description = "End time for range annotation (ISO-8601 or epoch ms). "
                    + "Optional; omit for point-in-time.", required = false)
            String timeEnd,
            @ToolParam(description = "Dashboard UID to attach to. Optional.",
                    required = false)
            String dashboardUid,
            @ToolParam(description = "Comma-separated tags. Optional.",
                    required = false)
            String tags) {
        long timeMs = parseTimeMs(time);
        long timeEndMs = timeEnd == null || timeEnd.isBlank() ? 0 : parseTimeMs(timeEnd);
        List<String> tagList = parseTags(tags);
        return this.client.createAnnotation(dashboardUid, timeMs, timeEndMs, text, tagList);
    }

    /**
     * Parses a time string as either epoch milliseconds or ISO-8601.
     */
    static long parseTimeMs(String input) {
        if (input == null || input.isBlank()) {
            return Instant.now().toEpochMilli();
        }
        String value = input.trim();
        return value.matches("\\d+") ? Long.parseLong(value) : Instant.parse(value).toEpochMilli();
    }

    private static List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
