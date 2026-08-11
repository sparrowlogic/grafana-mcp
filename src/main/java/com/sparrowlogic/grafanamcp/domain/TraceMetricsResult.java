package com.sparrowlogic.grafanamcp.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * A bounded projection of Tempo's TraceQL-metrics response ({@code /api/metrics/query_range}).
 *
 * <p>Tempo answers with protobuf-JSON, which omits default values: a bucket with no data arrives as
 * {@code {"timestampMs":"…"}} with no {@code value} at all, and label values are wrapped in
 * {@code {"stringValue":…}}. Both are normalised here, and series are capped the same way metric and
 * log responses are.
 *
 * @param query           the TraceQL metrics query that was executed
 * @param step            step that was requested
 * @param fromResolved    resolved window start (ISO-8601)
 * @param untilResolved   resolved window end (ISO-8601)
 * @param seriesReturned  series in this response
 * @param seriesTotal     series Tempo matched
 * @param seriesTruncated true when {@code seriesTotal > seriesReturned}
 * @param series          per-series values, highest total first
 * @param notice          what was bounded, or null
 */
public record TraceMetricsResult(
        String query,
        String step,
        String fromResolved,
        String untilResolved,
        int seriesReturned,
        int seriesTotal,
        boolean seriesTruncated,
        List<Series> series,
        String notice) {

    /** Projects the response, keeping at most {@code maxSeries} series. */
    public static TraceMetricsResult from(JsonNode root, String query, String step, String from,
            String until, int maxSeries) {
        List<Series> all = new ArrayList<>();
        JsonNode series = root == null ? null : root.path("series");
        if (series != null && series.isArray()) {
            series.forEach(node -> all.add(series(node)));
        }
        all.sort(Comparator.comparingDouble(Series::total).reversed());
        List<Series> kept = all.size() > maxSeries ? List.copyOf(all.subList(0, maxSeries)) : List.copyOf(all);
        return new TraceMetricsResult(query, step, from, until, kept.size(), all.size(),
                kept.size() < all.size(), kept,
                kept.size() < all.size()
                        ? "Showing the " + kept.size() + " largest of " + all.size() + " series — "
                                + "group by fewer attributes to see all of it."
                        : null);
    }

    private static Series series(JsonNode node) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (JsonNode label : node.path("labels")) {
            JsonNode value = label.path("value");
            String flattened = value.propertyStream().findFirst()
                    .map(field -> field.getValue().asString())
                    .orElse(value.asString(""));
            labels.put(label.path("key").asString(""), flattened);
        }
        List<List<Double>> points = new ArrayList<>();
        double total = 0d;
        for (JsonNode sample : node.path("samples")) {
            // protobuf-JSON omits a zero value entirely, so an absent 'value' means 0.
            double value = sample.path("value").asDouble(0d);
            double timestampMs = parse(sample.path("timestampMs").asString("0"));
            points.add(List.of(timestampMs, round(value)));
            total += value;
        }
        return new Series(labels, round(total), List.copyOf(points));
    }

    private static double parse(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return 0d;
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    /**
     * One series of a TraceQL metrics result.
     *
     * @param labels the series' labels, flattened
     * @param total  sum of its values across the window — what ranking is based on
     * @param points {@code [timestampMs, value]} pairs
     */
    public record Series(Map<String, String> labels, double total, List<List<Double>> points) {
    }
}
