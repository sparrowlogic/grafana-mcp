package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.cache.CachedLokiSearch;
import com.sparrowlogic.grafanamcp.cache.LokiSearchCacheService;
import com.sparrowlogic.grafanamcp.client.ExploreLinks;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments.Target;
import com.sparrowlogic.grafanamcp.client.LokiClient;
import com.sparrowlogic.grafanamcp.client.Times;
import com.sparrowlogic.grafanamcp.config.LokiProperties;
import com.sparrowlogic.grafanamcp.domain.CompactLogEntry;
import com.sparrowlogic.grafanamcp.domain.LogEntry;
import com.sparrowlogic.grafanamcp.domain.LogEntryDetail;
import com.sparrowlogic.grafanamcp.domain.LogVolumeResult;
import com.sparrowlogic.grafanamcp.domain.LokiSearchResult;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * MCP tools for searching Loki logs.
 *
 * <p>A log search is the easiest way to blow an MCP client's context window: an unfiltered
 * one-hour query on a busy environment matches tens of thousands of lines, each carrying a
 * repeated label set. These tools are shaped to prevent that, and the shape is the feature:
 *
 * <ul>
 *   <li><b>Fetch once, then paginate.</b> {@code search_loki_logs} pulls a bounded snapshot and
 *       returns compact page 0; {@code fetch_loki_page} and {@code get_loki_entry} read the cached
 *       snapshot without touching Loki again.</li>
 *   <li><b>Compact pages.</b> Lines are truncated and stream labels are emitted once in a per-page
 *       legend rather than on every line. Full detail is one call away via
 *       {@code get_loki_entry}.</li>
 *   <li><b>Count before you read.</b> {@code count_loki_logs} answers "how many, where, when"
 *       for a fraction of the tokens, so a search can be aimed at the interval that matters.</li>
 * </ul>
 */
@Component
public class LokiTools {

    /** Cap on buckets in a volume query, so a fine step over a long window cannot flood a response. */
    private static final int MAX_VOLUME_BUCKETS = 200;
    private static final int TARGET_VOLUME_BUCKETS = 60;
    private static final String LABEL_NAME_PATTERN = "[a-zA-Z_][a-zA-Z0-9_]*";

    private final LokiSearchCacheService cache;
    private final LokiClient client;
    private final ExploreLinks exploreLinks;
    private final GrafanaEnvironments environments;
    private final LokiProperties properties;

    public LokiTools(LokiSearchCacheService cache, LokiClient client, ExploreLinks exploreLinks,
            GrafanaEnvironments environments, LokiProperties properties) {
        this.cache = cache;
        this.client = client;
        this.exploreLinks = exploreLinks;
        this.environments = environments;
        this.properties = properties;
    }

    @Tool(name = "search_loki_logs",
            description = "Search Loki with LogQL. Fetches matching lines ONCE, caches them under a "
                    + "returned 'searchId', and returns the first page as compact entries (id, "
                    + "time, stream key, truncated line). Page with fetch_loki_page and expand "
                    + "one line with get_loki_entry — neither re-queries Loki. 'streams' is the "
                    + "label legend that each entry's 'stream' value resolves to.\n\nA stream "
                    + "selector with at least one label matcher is required: "
                    + "'{service_name=\"api\", level=\"ERROR\"} != \"healthcheck\"'. Use "
                    + "list_loki_labels / list_loki_label_values to discover what exists.\n\nIf "
                    + "'capped' is true the snapshot covers only part of the window — read "
                    + "'notice'/'coveredFrom', then narrow the query or use count_loki_logs to "
                    + "find the interval worth reading. Include the grafanaExploreUrl.")
    public LokiSearchResult searchLokiLogs(
            @ToolParam(description = "LogQL query, e.g. '{environment=\"production\"} |= \"ERROR\"'.")
            String query,
            @ToolParam(description = "Window start: relative (-1h), 'now', a date, ISO-8601, or epoch seconds. "
                    + "Default -1h.", required = false)
            String from,
            @ToolParam(description = "Window end, same formats. Default 'now'.", required = false)
            String until,
            @ToolParam(description = "'backward' (newest first, default) or 'forward' to find the first "
                    + "occurrence.", required = false)
            String direction,
            @ToolParam(description = "Entries per page. Default 25, max 200.", required = false)
            Integer pageSize,
            @ToolParam(description = "Loki datasource UID. Optional; discovered automatically.", required = false)
            String datasourceUid,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        String logql = requireQuery(query);
        Target target = this.environments.resolve(environment);
        String uid = this.client.resolveDatasourceUid(target, datasourceUid);
        LokiSearchCacheService.Handle handle = this.cache.search(target, uid, logql, from, until,
                direction);
        return this.page(handle.search(), 0, this.pageSize(pageSize), handle.reused());
    }

    @Tool(name = "fetch_loki_page",
            description = "Return another page from the cached Loki snapshot created by "
                    + "search_loki_logs; does NOT re-query Loki. Use the 'searchId' and "
                    + "'nextPage' from a prior result, with 'hasMore' saying whether more exist. "
                    + "Snapshots expire after a few minutes — if the searchId is unknown, run "
                    + "search_loki_logs again.")
    public LokiSearchResult fetchLokiPage(
            @ToolParam(description = "The searchId returned by search_loki_logs.")
            String searchId,
            @ToolParam(description = "Zero-based page index.")
            Integer page,
            @ToolParam(description = "Entries per page. Default 25, max 200.",
                    required = false)
            Integer pageSize) {
        CachedLokiSearch snapshot = this.cache.get(searchId);
        return this.page(snapshot, page == null ? 0 : Math.max(0, page), this.pageSize(pageSize), true);
    }

    @Tool(name = "get_loki_entry",
            description = "Return one log entry's complete line plus its stream's full label set, "
                    + "from a cached search snapshot. Reads the cache; does not re-query Loki. "
                    + "Use after search_loki_logs or fetch_loki_page to expand an entry whose "
                    + "compact line was truncated.")
    public LogEntryDetail getLokiEntry(
            @ToolParam(description = "The searchId returned by search_loki_logs.")
            String searchId,
            @ToolParam(description = "Entry id — the 'id' of a compact entry, e.g. 'e12'.")
            String entryId) {
        CachedLokiSearch snapshot = this.cache.get(searchId);
        LogEntry entry = snapshot.entries().stream()
                .filter(candidate -> candidate.id().equals(entryId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Entry '" + entryId + "' is not in snapshot '" + searchId + "'."));
        return new LogEntryDetail(entry.id(),
                Times.fromNanos(entry.timestampNs()).toString(),
                entry.timestampNs(),
                snapshot.streams().get(entry.streamIndex()),
                entry.line());
    }

    @Tool(name = "list_loki_labels",
            description = "Label names present in Loki for a window — the starting point for a LogQL "
                    + "stream selector. Follow up with list_loki_label_values.")
    public List<String> listLokiLabels(
            @ToolParam(description = "Window start: relative (-1h), 'now', a date, ISO-8601, or epoch seconds. "
                    + "Default -1h.", required = false)
            String from,
            @ToolParam(description = "Window end, same formats. Default 'now'.", required = false)
            String until,
            @ToolParam(description = "Loki datasource UID. Optional; discovered automatically.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        Target target = this.environments.resolve(environment);
        String uid = this.client.resolveDatasourceUid(target, datasourceUid);
        Window window = this.window(from, until);
        return this.client.labels(target, uid, window.startNs(), window.endNs());
    }

    @Tool(name = "list_loki_label_values",
            description = "Values of one Loki label in a window, e.g. every 'service_name' being "
                    + "logged, optionally restricted to a stream selector. Cheap next to a log "
                    + "search — use it to build a precise selector first.")
    public List<String> listLokiLabelValues(
            @ToolParam(description = "Label name, e.g. 'service_name'.")
            String label,
            @ToolParam(description = "Stream selector restricting the values, e.g. "
                    + "'{environment=\"production\"}'.", required = false)
            String selector,
            @ToolParam(description = "Window start: relative (-1h), 'now', a date, ISO-8601, or epoch seconds. "
                    + "Default -1h.", required = false)
            String from,
            @ToolParam(description = "Window end, same formats. Default 'now'.", required = false)
            String until,
            @ToolParam(description = "Loki datasource UID. Optional; discovered automatically.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        if (label == null || !label.trim().matches(LABEL_NAME_PATTERN)) {
            throw new IllegalArgumentException("'" + label + "' is not a valid Loki label name.");
        }
        Target target = this.environments.resolve(environment);
        String uid = this.client.resolveDatasourceUid(target, datasourceUid);
        Window window = this.window(from, until);
        return this.client.labelValues(target, uid, label.trim(), window.startNs(), window.endNs(),
                selector);
    }

    @Tool(name = "count_loki_logs",
            description = "Count matching log lines per time bucket instead of returning them — the "
                    + "cheap way to find WHERE and WHEN to look before spending tokens on lines. "
                    + "Takes the same LogQL log query as search_loki_logs (selector plus filters, "
                    + "not a metric expression) and wraps it in count_over_time.\n\n'groupBy' "
                    + "compares sources, e.g. groupBy='service_name' shows which service produces "
                    + "the errors; series are sorted by total and truncated, so prefer "
                    + "low-cardinality labels. Empty labels ({}) are the streams lacking that "
                    + "label. Include the grafanaExploreUrl.")
    public LogVolumeResult countLokiLogs(
            @ToolParam(description = "LogQL log query to count, e.g. '{environment=\"production\"} |= "
                    + "\"ERROR\"'.")
            String query,
            @ToolParam(description = "Window start: relative (-1h), 'now', a date, ISO-8601, or epoch seconds. "
                    + "Default -1h.", required = false)
            String from,
            @ToolParam(description = "Window end, same formats. Default 'now'.", required = false)
            String until,
            @ToolParam(description = "Bucket width, e.g. '5m'. Default about a sixtieth of the window; widened "
                    + "if it would make too many buckets.",
                    required = false)
            String step,
            @ToolParam(description = "Comma-separated label names to group by. Omit for a single total.",
                    required = false)
            String groupBy,
            @ToolParam(description = "Loki datasource UID. Optional; discovered automatically.",
                    required = false)
            String datasourceUid,
            @ToolParam(description = "Which Grafana. Required; see list_grafana_environments.")
            String environment) {
        String selector = requireLogSelector(query);
        Target target = this.environments.resolve(environment);
        String uid = this.client.resolveDatasourceUid(target, datasourceUid);
        Window window = this.window(from, until);
        long stepSeconds = this.stepSeconds(step, window);
        String logql = volumeQuery(selector, groupBy, stepSeconds);
        JsonNode matrix = this.client.metricQuery(target, uid, logql, window.startNs(), window.endNs(),
                stepSeconds);
        String exploreUrl = this.exploreLinks.explore(target, uid, "loki",
                Map.of("expr", logql, "queryType", "range"),
                window.start().toEpochMilli(), window.end().toEpochMilli());
        return LogVolumeResult.from(matrix, logql, stepSeconds + "s", window.start().toString(),
                window.end().toString(), this.properties.maxVolumeSeries(), exploreUrl);
    }

    /** Builds one page of compact entries, plus the legend for the streams it references. */
    private LokiSearchResult page(CachedLokiSearch snapshot, int page, int pageSize, boolean cached) {
        int total = snapshot.entries().size();
        int start = Math.min(page * pageSize, total);
        int end = Math.min(start + pageSize, total);
        List<LogEntry> slice = snapshot.entries().subList(start, end);
        Map<String, Map<String, String>> legend = new LinkedHashMap<>();
        for (LogEntry entry : slice) {
            legend.putIfAbsent(CompactLogEntry.streamKey(entry.streamIndex()),
                    snapshot.streams().get(entry.streamIndex()));
        }
        int lineCap = this.lineCap(slice.size());
        List<CompactLogEntry> entries = slice.stream()
                .map(entry -> CompactLogEntry.from(entry, lineCap))
                .toList();
        boolean hasMore = end < total;
        return new LokiSearchResult(
                snapshot.searchId(), snapshot.query(), snapshot.direction(),
                page, pageSize, entries.size(), total,
                hasMore, hasMore ? page + 1 : null,
                snapshot.capped(), cached, snapshot.windowStable(),
                snapshot.fromResolved(), snapshot.untilResolved(),
                total == 0 ? null : Times.fromNanos(snapshot.oldestNs()).toString(),
                total == 0 ? null : Times.fromNanos(snapshot.newestNs()).toString(),
                Map.copyOf(legend), entries,
                snapshot.exploreUrl(), this.notice(snapshot, total));
    }

    /**
     * The one thing worth saying about this snapshot beyond the numbers. A capped snapshot is the
     * dangerous case: it looks like a complete answer for the requested window but covers only the
     * newest (or oldest) slice of it.
     */
    private String notice(CachedLokiSearch snapshot, int total) {
        if (total == 0) {
            return "No lines matched. Check the selector's labels with list_loki_labels / "
                    + "list_loki_label_values, or widen the window.";
        }
        return snapshot.capped() ? this.cappedNotice(snapshot) : null;
    }

    /** Spells out exactly which slice of the requested window a capped snapshot really covers. */
    private String cappedNotice(CachedLokiSearch snapshot) {
        String edge = LokiSearchCacheService.BACKWARD.equals(snapshot.direction()) ? "newest" : "oldest";
        return "Truncated at the fetch cap of " + this.properties.maxFetch() + " lines: this snapshot "
                + "holds only the " + edge + " matches, covering "
                + Times.fromNanos(snapshot.oldestNs()) + " to "
                + Times.fromNanos(snapshot.newestNs()) + " — NOT the whole requested window ("
                + snapshot.fromResolved() + " to " + snapshot.untilResolved() + "). Narrow the query "
                + "with line filters, shorten the window, or run count_loki_logs first to find the "
                + "interval that matters.";
    }

    /** Resolves a from/until pair once, for the tools that query Loki directly. */
    private Window window(String from, String until) {
        Instant now = Instant.now();
        Instant start = Times.resolve(from == null || from.isBlank() ? "-1h" : from, now);
        Instant end = Times.resolve(until, now);
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Window start (" + start + ") must be before end ("
                    + end + ").");
        }
        return new Window(start, end);
    }

    /**
     * Bucket width for a volume query: the caller's step if given, otherwise roughly a sixtieth of
     * the window, then widened if it would produce more buckets than a response should carry.
     */
    private long stepSeconds(String step, Window window) {
        long windowSeconds = Math.max(1L, window.end().getEpochSecond() - window.start().getEpochSecond());
        long requested = step == null || step.isBlank()
                ? Math.max(1L, windowSeconds / TARGET_VOLUME_BUCKETS)
                : Times.spanSeconds(step);
        long minimum = Math.max(1L, (windowSeconds + MAX_VOLUME_BUCKETS - 1) / MAX_VOLUME_BUCKETS);
        return Math.max(requested, minimum);
    }

    /**
     * Per-line truncation limit for a page: the page's character budget spread across its entries,
     * bounded either way. A handful of long lines stay readable; a 200-entry page gets clipped hard.
     * Either way the full line is one {@code get_loki_entry} call away.
     */
    private int lineCap(int entryCount) {
        int max = this.properties.maxLineChars();
        if (entryCount <= 0) {
            return max;
        }
        int share = this.properties.pageCharBudget() / entryCount;
        return Math.min(max, Math.max(this.properties.minLineChars(), share));
    }

    private int pageSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return this.properties.defaultPageSize();
        }
        return Math.min(requested, this.properties.maxPageSize());
    }

    /** Wraps a log selector in {@code count_over_time}, grouped by the requested labels. */
    private static String volumeQuery(String selector, String groupBy, long stepSeconds) {
        String counted = "count_over_time(" + selector + "[" + stepSeconds + "s])";
        List<String> labels = groupBy == null || groupBy.isBlank()
                ? List.of()
                : Arrays.stream(groupBy.split(",")).map(String::trim)
                        .filter(name -> !name.isEmpty()).toList();
        labels.stream()
                .filter(name -> !name.matches(LABEL_NAME_PATTERN))
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalArgumentException("'" + name + "' is not a valid label name.");
                });
        if (labels.isEmpty()) {
            return "sum(" + counted + ")";
        }
        return "sum by (" + String.join(", ", labels) + ") (" + counted + ")";
    }

    private static String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("A LogQL query is required, e.g. "
                    + "'{environment=\"production\"} |= \"ERROR\"'.");
        }
        return query.trim();
    }

    /**
     * Rejects a metric expression up front. Wrapping one in {@code count_over_time} produces a
     * LogQL parse error from Loki that is far less actionable than saying so here.
     */
    private static String requireLogSelector(String query) {
        String selector = requireQuery(query);
        String lowered = selector.toLowerCase(Locale.ROOT);
        boolean metric = lowered.contains("_over_time") || lowered.contains("rate(")
                || lowered.startsWith("sum") || lowered.contains("quantile");
        if (metric) {
            throw new IllegalArgumentException("count_loki_logs takes a LOG query (a stream selector "
                    + "with optional line filters), not a metric expression like '" + selector
                    + "'. Pass e.g. '{environment=\"production\"} |= \"ERROR\"' and it will be wrapped "
                    + "in count_over_time for you.");
        }
        return selector;
    }

    /** A resolved query window, in both instant and Loki-nanosecond form. */
    private record Window(Instant start, Instant end) {

        long startNs() {
            return Times.toNanos(this.start);
        }

        long endNs() {
            return Times.toNanos(this.end);
        }
    }
}
