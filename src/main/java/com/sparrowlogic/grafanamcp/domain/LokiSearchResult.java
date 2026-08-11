package com.sparrowlogic.grafanamcp.domain;

import java.util.List;
import java.util.Map;

/**
 * One page of a cached Loki log search. The matching entries are fetched once and cached
 * server-side under {@code searchId}; this record is a window into that snapshot. Page through it
 * with {@code fetch_loki_page(searchId, page)} and pull an entry's full line with
 * {@code get_loki_entry(searchId, entryId)} — neither re-queries Loki.
 *
 * <p>{@code streams} is the legend for this page: each compact entry's {@code stream} key resolves
 * to a label set here, so labels are sent once per stream rather than once per line.
 *
 * @param searchId          id of the cached snapshot — pass to fetch_loki_page / get_loki_entry
 * @param query             the LogQL query the snapshot was fetched with
 * @param direction         {@code backward} (newest first) or {@code forward} (oldest first)
 * @param page              zero-based page index of this response
 * @param pageSize          entries per page
 * @param returned          number of entries on this page
 * @param totalFetched      total entries in the cached snapshot
 * @param hasMore           whether further pages exist in the snapshot
 * @param nextPage          the next page index to request, or null when this is the last page
 * @param capped            true if Loki held more entries than the fetch cap — see
 *                          {@code coveredFrom}/{@code coveredUntil} for what the snapshot spans
 * @param cached            true if served from a pre-existing snapshot rather than a fresh fetch
 * @param windowStable      true if the window end is old enough that the data is immutable
 * @param fromResolved      the requested window start (ISO-8601)
 * @param untilResolved     the requested window end (ISO-8601)
 * @param coveredFrom       time of the oldest entry actually in the snapshot (ISO-8601), or null
 *                          when the snapshot is empty
 * @param coveredUntil      time of the newest entry actually in the snapshot (ISO-8601), or null
 *                          when the snapshot is empty
 * @param streams           legend: stream key ({@code s0}, …) to that stream's labels, for the
 *                          streams referenced on this page only
 * @param entries           the compact entries on this page
 * @param grafanaExploreUrl link that opens this query and window in Grafana Explore
 * @param notice            a warning worth surfacing to the user (e.g. truncated coverage), or
 *                          null when there is nothing to flag
 */
public record LokiSearchResult(
        String searchId,
        String query,
        String direction,
        int page,
        int pageSize,
        int returned,
        int totalFetched,
        boolean hasMore,
        Integer nextPage,
        boolean capped,
        boolean cached,
        boolean windowStable,
        String fromResolved,
        String untilResolved,
        String coveredFrom,
        String coveredUntil,
        Map<String, Map<String, String>> streams,
        List<CompactLogEntry> entries,
        String grafanaExploreUrl,
        String notice) {
}
