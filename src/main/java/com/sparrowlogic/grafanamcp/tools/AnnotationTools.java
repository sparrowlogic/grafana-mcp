package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
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
    private final GrafanaEnvironments environments;

    public AnnotationTools(GrafanaClient client, GrafanaEnvironments environments) {
        this.client = client;
        this.environments = environments;
    }

    @Tool(name = "list_annotations",
            description = "List Grafana annotations in a time range, optionally filtered by dashboard "
                    + "UID or tags.")
    public List<Annotation> listAnnotations(
            @ToolParam(description = "Start of time range (ISO-8601 or epoch ms).")
            String from,
            @ToolParam(description = "End of time range (ISO-8601 or epoch ms).")
            String to,
            @ToolParam(description = "Dashboard UID to filter by.",
                    required = false)
            String dashboardUid,
            @ToolParam(description = "Comma-separated tags.",
                    required = false)
            String tags,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        long fromMs = parseTimeMs(from);
        long toMs = parseTimeMs(to);
        List<String> tagList = parseTags(tags);
        return this.client.listAnnotations(this.environments.resolve(environment), fromMs, toMs,
                dashboardUid, tagList);
    }

    @Tool(name = "create_annotation",
            description = "Create a Grafana annotation — a point in time, or a range if timeEnd is "
                    + "given. Useful for marking deploys and incidents on dashboards.")
    public JsonNode createAnnotation(
            @ToolParam(description = "Annotation text.")
            String text,
            @ToolParam(description = "Start time (ISO-8601 or epoch ms).")
            String time,
            @ToolParam(description = "End time for a range annotation; omit for point-in-time.", required = false)
            String timeEnd,
            @ToolParam(description = "Dashboard UID to attach to.",
                    required = false)
            String dashboardUid,
            @ToolParam(description = "Comma-separated tags.",
                    required = false)
            String tags,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        long timeMs = parseTimeMs(time);
        long timeEndMs = timeEnd == null || timeEnd.isBlank() ? 0 : parseTimeMs(timeEnd);
        List<String> tagList = parseTags(tags);
        return this.client.createAnnotation(this.environments.resolve(environment), dashboardUid,
                timeMs, timeEndMs, text, tagList);
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
