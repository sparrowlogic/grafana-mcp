package com.sparrowlogic.grafanamcp.client;

import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments.Target;
import com.sparrowlogic.grafanamcp.config.LokiProperties;
import com.sparrowlogic.grafanamcp.domain.FetchedLogs;
import com.sparrowlogic.grafanamcp.domain.LogEntry;
import com.sparrowlogic.grafanamcp.domain.LokiQueryResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;

/**
 * Read-only client for Loki's query API, reached through Grafana's datasource proxy so the same
 * service-account token authenticates it.
 *
 * <p>Addressing, encoding and error mapping belong to {@link DatasourceProxy}, which every signal
 * type shares; what is specific to Loki lives here.
 *
 * <p>{@link #fetchEntries} is where log-volume control lives: Loki caps a single {@code query_range}
 * at its own {@code limit}, so covering a window means walking it in slices. This walks it once,
 * eagerly, up to {@code grafana.loki.max-fetch} entries, so a caller can cache one consistent
 * snapshot and page through it instead of re-querying Loki per page.
 */
@Component
public class LokiClient {

    /** Loki rejects a {@code limit} above its own {@code max_entries_limit_per_query}. */
    private static final int LOKI_MAX_LIMIT = 5000;

    private static final String QUERY_PARAM = "query";
    private static final String START_PARAM = "start";
    private static final String END_PARAM = "end";
    private static final String LOKI_API = "/loki/api/v1";

    private final DatasourceProxy proxy;
    private final LokiProperties properties;

    public LokiClient(DatasourceProxy proxy, LokiProperties properties) {
        this.proxy = proxy;
        this.properties = properties;
    }

    /**
     * Resolves which Loki datasource to query: an explicit UID wins, then
     * {@code grafana.loki.datasource-uid}, then the first datasource of type {@code loki} found in
     * Grafana (discovered once and remembered, so routine calls cost no extra request).
     *
     * @throws IllegalStateException if Grafana has no Loki datasource configured
     */
    public String resolveDatasourceUid(Target target, String requestedUid) {
        String requested = requestedUid == null || requestedUid.isBlank()
                ? this.properties.datasourceUid()
                : requestedUid;
        return this.proxy.resolveUid(target, requested, "loki");
    }

    /**
     * Fetches log entries for a LogQL query across the whole window, walking it in slices until
     * either the window is covered or {@code grafana.loki.max-fetch} entries have been collected.
     *
     * <p>Loki has no result cursor: the next slice is derived from the boundary timestamp of the
     * previous one. Slices deliberately overlap by one nanosecond — Loki's {@code start} is
     * inclusive and {@code end} exclusive, and many entries can share a nanosecond, so cutting
     * exactly at the boundary would drop entries. The re-fetched overlap is removed by de-duping
     * on (timestamp, labels, line).
     *
     * @param uid       Loki datasource UID
     * @param query     LogQL log query (a stream selector plus optional line/label filters)
     * @param startNs   window start, nanoseconds since the epoch (inclusive)
     * @param endNs     window end, nanoseconds since the epoch (exclusive)
     * @param backward  true for newest-first (Loki {@code direction=backward}), false for oldest-first
     */
    public FetchedLogs fetchEntries(Target target, String uid, String query, long startNs, long endNs,
            boolean backward) {
        int limit = Math.clamp(this.properties.pageLimit(), 1, LOKI_MAX_LIMIT);
        EntryCollector collector = new EntryCollector(Math.max(1, this.properties.maxFetch()));
        long cursor = backward ? endNs : startNs;
        // True while the last slice came back full, i.e. Loki hit its own limit and unread entries
        // remain in the window. Cleared only by positively covering the window.
        boolean unreadRemains = false;
        while (!collector.full()) {
            List<RawEntry> slice = this.fetchSlice(target, uid, query,
                    new Slice(startNs, endNs, cursor, limit), backward);
            int added = collector.absorb(slice);
            long next = slice.isEmpty() ? cursor : nextCursor(slice, backward);
            unreadRemains = slice.size() >= limit;
            if (!unreadRemains || cannotAdvance(added, next, cursor)) {
                break;
            }
            if (outsideWindow(next, startNs, endNs, backward)) {
                unreadRemains = false;
                break;
            }
            cursor = next;
        }
        return collector.result(unreadRemains || collector.overflowed());
    }

    /** GET {@code /loki/api/v1/labels} — label names present in the window. */
    public List<String> labels(Target target, String uid, long startNs, long endNs) {
        return stringList(this.json(target,
                this.proxy.uri(target, uid, LOKI_API + "/labels", window(startNs, endNs))));
    }

    /**
     * GET {@code /loki/api/v1/label/{label}/values} — the values of one label in the window,
     * optionally restricted to streams matching a selector.
     */
    public List<String> labelValues(Target target, String uid, String label, long startNs, long endNs,
            String selector) {
        Map<String, String> params = window(startNs, endNs);
        if (selector != null && !selector.isBlank()) {
            params.put(QUERY_PARAM, selector);
        }
        JsonNode body = this.json(target, this.proxy.uri(target, uid, LOKI_API + "/label/"
                + UriUtils.encodePathSegment(label, StandardCharsets.UTF_8) + "/values", params));
        return stringList(body);
    }

    /**
     * GET {@code /loki/api/v1/query_range} for a metric query (e.g. {@code count_over_time}),
     * returning the matrix response verbatim for projection by the caller.
     *
     * @param stepSeconds bucket width in seconds
     */
    public JsonNode metricQuery(Target target, String uid, String logql, long startNs, long endNs,
            long stepSeconds) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(QUERY_PARAM, logql);
        params.putAll(window(startNs, endNs));
        params.put("step", stepSeconds + "s");
        return this.json(target, this.proxy.uri(target, uid, LOKI_API + "/query_range", params));
    }

    private JsonNode json(Target target, URI uri) {
        return this.proxy.get(target, uri, JsonNode.class);
    }

    /** The start/end pair every Loki endpoint takes, in a fixed order so URLs are predictable. */
    private static Map<String, String> window(long startNs, long endNs) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(START_PARAM, Long.toString(startNs));
        params.put(END_PARAM, Long.toString(endNs));
        return params;
    }

    /** The next slice of the walk, flattened and ordered; empty once the cursor closes the window. */
    private List<RawEntry> fetchSlice(Target target, String uid, String query, Slice slice,
            boolean backward) {
        long startNs = slice.startNs();
        long endNs = slice.endNs();
        long cursor = slice.cursor();
        int limit = slice.limit();
        long sliceStart = backward ? startNs : cursor;
        long sliceEnd = backward ? cursor : endNs;
        if (sliceStart >= sliceEnd) {
            return List.of();
        }
        return flatten(this.queryRange(target, uid, query, sliceStart, sliceEnd, limit, backward),
                backward);
    }

    /** One slice of a log query. */
    private LokiQueryResponse queryRange(Target target, String uid, String query, long startNs,
            long endNs, int limit, boolean backward) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(QUERY_PARAM, query);
        params.putAll(window(startNs, endNs));
        params.put("limit", Integer.toString(limit));
        params.put("direction", backward ? "backward" : "forward");
        return this.proxy.get(target, this.proxy.uri(target, uid, LOKI_API + "/query_range", params),
                LokiQueryResponse.class);
    }

    /**
     * Merges the per-stream entries of one response into a single list ordered the way the query
     * was: newest first for {@code backward}. Loki orders entries within a stream but not across
     * streams, so the merge has to sort.
     */
    private static List<RawEntry> flatten(LokiQueryResponse response, boolean backward) {
        List<RawEntry> flat = new ArrayList<>();
        for (LokiQueryResponse.Stream stream : streamsOf(response)) {
            Map<String, String> labels = stream.stream() == null ? Map.of() : stream.stream();
            List<JsonNode> values = stream.values() == null ? List.of() : stream.values();
            for (JsonNode value : values) {
                flat.add(new RawEntry(Long.parseLong(value.path(0).asString("0")),
                        value.path(1).asString(""), labels));
            }
        }
        Comparator<RawEntry> byTime = Comparator.comparingLong(RawEntry::timestampNs);
        flat.sort(backward ? byTime.reversed() : byTime);
        return flat;
    }

    /** The streams of a response, tolerating a null body or a query that matched nothing. */
    private static List<LokiQueryResponse.Stream> streamsOf(LokiQueryResponse response) {
        if (response == null || response.data() == null || response.data().result() == null) {
            return List.of();
        }
        return response.data().result();
    }

    /**
     * Start of the next slice: one nanosecond past the boundary entry when walking backward (so
     * the boundary nanosecond stays inside the window), the boundary itself when walking forward
     * (where {@code start} is already inclusive). Either way the overlap is de-duped.
     */
    private static long nextCursor(List<RawEntry> slice, boolean backward) {
        long boundary = slice.getLast().timestampNs();
        return backward ? boundary + 1 : boundary;
    }

    /** True once the next cursor would step past the requested window. */
    private static boolean outsideWindow(long cursor, long startNs, long endNs, boolean backward) {
        return backward ? cursor <= startNs : cursor >= endNs;
    }

    /**
     * True when another slice would repeat the last one: nothing new arrived, or the boundary
     * timestamp did not move. Happens when a single nanosecond holds more entries than Loki's
     * limit — rare, but it would otherwise loop forever.
     */
    private static boolean cannotAdvance(int added, long next, long cursor) {
        return added == 0 || next == cursor;
    }

    private static List<String> stringList(JsonNode body) {
        List<String> values = new ArrayList<>();
        JsonNode data = body == null ? null : body.path("data");
        if (data != null && data.isArray()) {
            data.forEach(node -> values.add(node.asString("")));
        }
        return List.copyOf(values);
    }

    /** One step of the window walk: the query window, where the walk has reached, and the cap. */
    private record Slice(long startNs, long endNs, long cursor, int limit) {
    }

    /** A flattened Loki entry before ids and stream indexes are assigned. */
    private record RawEntry(long timestampNs, String line, Map<String, String> labels) {
    }

    /**
     * Accumulates entries across slices: assigns ids, folds repeated label sets into stream
     * indexes, drops the one-nanosecond slice overlap, and stops at the fetch cap.
     */
    private static final class EntryCollector {

        private final int cap;
        private final List<LogEntry> entries = new ArrayList<>();
        private final List<Map<String, String>> streams = new ArrayList<>();
        private final Map<Map<String, String>, Integer> streamIndexes = new HashMap<>();
        private final Set<String> seen = new HashSet<>();
        /** True if the cap was reached with entries of the current slice still unread. */
        private boolean overflowed;

        EntryCollector(int cap) {
            this.cap = cap;
        }

        /** Adds the unseen entries of one slice, in order, and reports how many were added. */
        int absorb(List<RawEntry> slice) {
            int added = 0;
            for (RawEntry raw : slice) {
                if (this.full()) {
                    this.overflowed = true;
                    break;
                }
                if (this.seen.add(raw.timestampNs() + " " + raw.labels().hashCode()
                        + " " + raw.line())) {
                    this.entries.add(new LogEntry("e" + this.entries.size(), raw.timestampNs(),
                            this.streamIndex(raw.labels()), raw.line()));
                    added++;
                }
            }
            return added;
        }

        boolean full() {
            return this.entries.size() >= this.cap;
        }

        boolean overflowed() {
            return this.overflowed;
        }

        FetchedLogs result(boolean capped) {
            return new FetchedLogs(List.copyOf(this.entries), List.copyOf(this.streams), capped);
        }

        /** Index of this label set, registering it on first sight. */
        private int streamIndex(Map<String, String> labels) {
            return this.streamIndexes.computeIfAbsent(labels, key -> {
                this.streams.add(key);
                return this.streams.size() - 1;
            });
        }
    }
}
