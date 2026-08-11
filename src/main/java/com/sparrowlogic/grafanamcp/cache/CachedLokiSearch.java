package com.sparrowlogic.grafanamcp.cache;

import com.sparrowlogic.grafanamcp.domain.LogEntry;
import java.util.List;
import java.util.Map;

/**
 * A cached snapshot of a Loki log search: the entries fetched once from Loki, plus the metadata
 * needed to page through them and decide reuse. Held in memory by {@link LokiSearchCacheService}
 * for the lifetime of the (single, long-lived) stdio server process.
 *
 * @param searchId        opaque id handed to clients for fetch_loki_page / get_loki_entry
 * @param datasourceUid   the Loki datasource the entries came from
 * @param query           the LogQL query that produced this snapshot
 * @param direction       {@code backward} (newest first) or {@code forward} (oldest first)
 * @param fromResolved    resolved window start (ISO-8601)
 * @param untilResolved   resolved window end (ISO-8601)
 * @param entries         the entries with complete log lines, in query order
 * @param streams         distinct stream label sets; {@link LogEntry#streamIndex()} indexes this
 * @param capped          true if Loki held more entries than the fetch cap
 * @param windowStable    true if the window is immutable (end older than the stable threshold)
 * @param exploreUrl      Grafana Explore link for this query and window
 * @param createdAtMillis when the snapshot was fetched
 * @param expiresAtMillis when the snapshot should be evicted
 */
public record CachedLokiSearch(
        String searchId,
        String datasourceUid,
        String query,
        String direction,
        String fromResolved,
        String untilResolved,
        List<LogEntry> entries,
        List<Map<String, String>> streams,
        boolean capped,
        boolean windowStable,
        String exploreUrl,
        long createdAtMillis,
        long expiresAtMillis) {

    /**
     * Timestamp of the oldest entry held, or {@code 0} when the snapshot is empty. With a capped
     * snapshot this — not the requested window start — is where coverage actually begins.
     */
    public long oldestNs() {
        return this.entries.stream().mapToLong(LogEntry::timestampNs).min().orElse(0L);
    }

    /** Timestamp of the newest entry held, or {@code 0} when the snapshot is empty. */
    public long newestNs() {
        return this.entries.stream().mapToLong(LogEntry::timestampNs).max().orElse(0L);
    }

    boolean isExpired(long nowMillis) {
        return nowMillis >= this.expiresAtMillis;
    }
}
