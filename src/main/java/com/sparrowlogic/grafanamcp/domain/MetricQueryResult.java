package com.sparrowlogic.grafanamcp.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * A bounded projection of a Prometheus {@code query_range} matrix.
 *
 * <p>The raw response is the largest single response this server can produce — {@code up} over ten
 * minutes measured 748 series and ~330 000 characters — so nothing is passed through verbatim.
 * Series are ranked by peak value and capped; each one is reduced to statistics; and samples are
 * included only when explicitly requested, bucketed down so a long range cannot flood the response.
 *
 * @param query             the PromQL that was executed
 * @param step              query resolution step
 * @param fromResolved      resolved range start (ISO-8601)
 * @param untilResolved     resolved range end (ISO-8601)
 * @param seriesReturned    series in this response
 * @param seriesTotal       series Prometheus matched
 * @param seriesTruncated   true when {@code seriesTotal > seriesReturned} — aggregate the query
 *                          (e.g. {@code sum by (...)}) rather than paging through series
 * @param totalPoints       samples Prometheus returned across all matched series
 * @param pointAggregation  {@code none} when samples were omitted, {@code raw} when returned
 *                          as-is, {@code max-per-bucket} when bucketed down
 * @param series            per-series statistics, highest peak first
 * @param grafanaExploreUrl link that opens this query and range in Grafana Explore
 * @param notice            what was bounded and how to get more, or null when nothing was cut
 */
public record MetricQueryResult(
        String query,
        String step,
        String fromResolved,
        String untilResolved,
        int seriesReturned,
        int seriesTotal,
        long totalPoints,
        boolean seriesTruncated,
        String pointAggregation,
        List<Series> series,
        String grafanaExploreUrl,
        String notice) {

    private static final String NONE = "none";
    private static final String RAW = "raw";
    private static final String BUCKETED = "max-per-bucket";

    /**
     * Projects a matrix response into a bounded result.
     *
     * @param root   parsed {@code query_range} response
     * @param bounds what to keep — samples or not, and how much of each dimension
     */
    public static MetricQueryResult from(JsonNode root, String query, String step, String from,
            String until, Bounds bounds, String exploreUrl) {
        boolean includePoints = bounds.includePoints();
        int maxSeries = bounds.maxSeries();
        int maxPoints = bounds.maxPoints();
        List<Series> all = new ArrayList<>();
        long totalPoints = 0L;
        JsonNode result = root == null ? null : root.path("data").path("result");
        boolean bucketed = false;
        if (result != null && result.isArray()) {
            for (JsonNode node : result) {
                List<double[]> samples = samples(node.path("values"));
                totalPoints += samples.size();
                boolean over = includePoints && samples.size() > maxPoints;
                bucketed = bucketed || over;
                all.add(series(node, samples, includePoints, maxPoints));
            }
        }
        all.sort(Comparator.comparingDouble((Series series) -> series.stats().max()).reversed());
        List<Series> kept = all.size() > maxSeries ? List.copyOf(all.subList(0, maxSeries)) : List.copyOf(all);
        String aggregation = aggregation(includePoints, bucketed);
        return new MetricQueryResult(query, step, from, until, kept.size(), all.size(), totalPoints,
                kept.size() < all.size(), aggregation, kept, exploreUrl,
                notice(all.size(), kept.size(), includePoints, bucketed));
    }

    private static String aggregation(boolean includePoints, boolean bucketed) {
        if (!includePoints) {
            return NONE;
        }
        return bucketed ? BUCKETED : RAW;
    }

    /** Reads the numeric samples of one series, skipping any non-finite value Prometheus reports. */
    private static List<double[]> samples(JsonNode values) {
        List<double[]> samples = new ArrayList<>();
        if (!values.isArray()) {
            return samples;
        }
        for (JsonNode point : values) {
            double value = parse(point.path(1).asString("NaN"));
            if (Double.isFinite(value)) {
                samples.add(new double[] {point.path(0).asDouble(), value});
            }
        }
        return samples;
    }

    private static double parse(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private static Series series(JsonNode node, List<double[]> samples, boolean includePoints,
            int maxPoints) {
        Map<String, String> labels = new LinkedHashMap<>();
        node.path("metric").propertyStream()
                .forEach(field -> labels.put(field.getKey(), field.getValue().asString()));
        return new Series(labels, stats(samples),
                includePoints ? points(samples, maxPoints) : null);
    }

    private static Stats stats(List<double[]> samples) {
        if (samples.isEmpty()) {
            return new Stats(0, 0d, 0d, 0d, 0d, null, null);
        }
        double min = samples.getFirst()[1];
        double max = min;
        double sum = 0d;
        for (double[] sample : samples) {
            min = Math.min(min, sample[1]);
            max = Math.max(max, sample[1]);
            sum += sample[1];
        }
        return new Stats(samples.size(), round(min), round(sum / samples.size()), round(max),
                round(samples.getLast()[1]),
                Instant.ofEpochSecond((long) samples.getFirst()[0]).toString(),
                Instant.ofEpochSecond((long) samples.getLast()[0]).toString());
    }

    /**
     * Samples for one series, bucketed down to {@code maxPoints} if needed. Each bucket keeps its
     * <em>maximum</em> rather than its mean: averaging is what hides the spike you went looking for.
     */
    private static List<List<Double>> points(List<double[]> samples, int maxPoints) {
        if (samples.size() <= maxPoints) {
            return samples.stream().map(sample -> List.of(sample[0], round(sample[1]))).toList();
        }
        List<List<Double>> bucketed = new ArrayList<>();
        int bucketSize = (samples.size() + maxPoints - 1) / maxPoints;
        for (int start = 0; start < samples.size(); start += bucketSize) {
            List<double[]> bucket = samples.subList(start, Math.min(start + bucketSize, samples.size()));
            double peak = bucket.stream().mapToDouble(sample -> sample[1]).max().orElse(0d);
            bucketed.add(List.of(bucket.getFirst()[0], round(peak)));
        }
        return List.copyOf(bucketed);
    }

    private static String notice(int total, int kept, boolean includePoints, boolean bucketed) {
        List<String> notes = new ArrayList<>();
        if (kept < total) {
            notes.add("Showing the " + kept + " highest-peak series of " + total
                    + " — aggregate the query (e.g. 'sum by (instance) (...)') to see all of it");
        }
        if (!includePoints) {
            notes.add("Samples omitted: each series is summarised. Pass detail='points' for the "
                    + "time series itself");
        }
        if (bucketed) {
            notes.add("Samples bucketed to the configured maximum, keeping each bucket's peak — "
                    + "shorten the range or widen the step for exact samples");
        }
        return notes.isEmpty() ? null : String.join(". ", notes) + ".";
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    /**
     * How much of a response to keep.
     *
     * @param includePoints whether to carry samples as well as statistics
     * @param maxSeries     series to keep after ranking by peak
     * @param maxPoints     samples to keep per series
     */
    public record Bounds(boolean includePoints, int maxSeries, int maxPoints) {
    }

    /**
     * One series: its labels, its statistics, and — only when requested — its samples.
     *
     * @param labels the series' label set
     * @param stats  summary of the whole series, computed before any bucketing
     * @param points {@code [epochSeconds, value]} pairs, or null when samples were not requested
     */
    public record Series(Map<String, String> labels, Stats stats, List<List<Double>> points) {
    }

    /**
     * Summary of one series over the queried range.
     *
     * @param points    number of samples the series held
     * @param min       lowest value
     * @param avg       mean value
     * @param max       highest value — what series ranking is based on
     * @param last      most recent value
     * @param firstTime time of the first sample (ISO-8601), or null when the series was empty
     * @param lastTime  time of the last sample (ISO-8601), or null when the series was empty
     */
    public record Stats(int points, double min, double avg, double max, double last,
            String firstTime, String lastTime) {
    }
}
