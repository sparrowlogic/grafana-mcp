package com.sparrowlogic.grafanamcp.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Log volume over time: the result of counting matching lines per interval instead of returning
 * them. This is the cheap way to find where the interesting logs are — a spike, a specific
 * service, a specific hour — before spending tokens on the lines themselves.
 *
 * <p>Built from a Loki metric query ({@code count_over_time}), whose response is a matrix of
 * series. Series are sorted by descending total and truncated to a configured maximum so a
 * high-cardinality grouping cannot blow up the response.
 *
 * @param query             the LogQL metric query that was executed
 * @param step              bucket width actually used (e.g. {@code 5m}) — may be coarser than
 *                          requested so the number of buckets stays bounded
 * @param fromResolved      the resolved window start (ISO-8601)
 * @param untilResolved     the resolved window end (ISO-8601)
 * @param total             total matching lines across all series in the window
 * @param seriesReturned    number of series in this response
 * @param seriesTotal       number of series Loki matched
 * @param seriesTruncated   true if {@code seriesTotal > seriesReturned} — narrow the grouping
 * @param series            per-series counts, highest total first
 * @param grafanaExploreUrl link that opens this query and window in Grafana Explore
 */
public record LogVolumeResult(
        String query,
        String step,
        String fromResolved,
        String untilResolved,
        long total,
        int seriesReturned,
        int seriesTotal,
        boolean seriesTruncated,
        List<Series> series,
        String grafanaExploreUrl) {

    /**
     * Projects a Loki matrix response into a bounded volume result.
     *
     * @param root      the parsed {@code query_range} response
     * @param query     the LogQL metric query that produced it
     * @param step      the bucket width used
     * @param from      resolved window start, for echoing back
     * @param until     resolved window end, for echoing back
     * @param maxSeries how many series to keep after sorting by descending total
     * @param exploreUrl Grafana Explore link for the query
     */
    public static LogVolumeResult from(JsonNode root, String query, String step, String from,
            String until, int maxSeries, String exploreUrl) {
        List<Series> all = new ArrayList<>();
        JsonNode result = root == null ? null : root.path("data").path("result");
        if (result != null && result.isArray()) {
            for (JsonNode node : result) {
                all.add(series(node));
            }
        }
        all.sort(Comparator.comparingLong(Series::total).reversed());
        long total = all.stream().mapToLong(Series::total).sum();
        List<Series> kept = all.size() > maxSeries ? List.copyOf(all.subList(0, maxSeries)) : List.copyOf(all);
        return new LogVolumeResult(query, step, from, until, total, kept.size(), all.size(),
                kept.size() < all.size(), kept, exploreUrl);
    }

    /** Reads one matrix series: its labels plus {@code [epochSeconds, count]} buckets. */
    private static Series series(JsonNode node) {
        Map<String, String> labels = new LinkedHashMap<>();
        node.path("metric").propertyStream()
                .forEach(field -> labels.put(field.getKey(), field.getValue().asString()));
        List<List<Long>> points = new ArrayList<>();
        long total = 0L;
        for (JsonNode point : node.path("values")) {
            long at = point.path(0).asLong();
            long count = Math.round(Double.parseDouble(point.path(1).asString("0")));
            points.add(List.of(at, count));
            total += count;
        }
        return new Series(labels, total, points);
    }

    /**
     * Counts for one label set.
     *
     * @param labels the series' labels ({@code {}} when the query is not grouped)
     * @param total  total matching lines for this series across the window
     * @param points one {@code [epochSeconds, count]} pair per non-empty bucket
     */
    public record Series(Map<String, String> labels, long total, List<List<Long>> points) {
    }
}
