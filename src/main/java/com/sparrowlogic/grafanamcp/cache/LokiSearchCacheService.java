package com.sparrowlogic.grafanamcp.cache;

import com.sparrowlogic.grafanamcp.client.ExploreLinks;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments.Target;
import com.sparrowlogic.grafanamcp.client.LokiClient;
import com.sparrowlogic.grafanamcp.client.Times;
import com.sparrowlogic.grafanamcp.config.LokiProperties;
import com.sparrowlogic.grafanamcp.domain.FetchedLogs;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory store of Loki search snapshots. The stdio MCP server is a single, long-lived process,
 * so a process-local cache lets a large result set be fetched <em>once</em> and then paged through
 * cheaply — the difference between one Loki query per conversation and one per page.
 *
 * <p>Stability rule (correctness-critical): a window whose resolved end is older than
 * {@code grafana.loki.stable-window-minutes} is immutable — ingestion has settled — so re-issuing
 * the same query reuses the snapshot. A live window (end ≈ now) returns changing data, so it is
 * <em>never</em> reused: each call mints a fresh snapshot. Stable snapshots also live longer (TTL)
 * than live ones.
 */
@Service
public class LokiSearchCacheService {

    /** Loki's newest-first direction, and the default for a log search. */
    public static final String BACKWARD = "backward";
    /** Loki's oldest-first direction — the way to find the first occurrence in a window. */
    public static final String FORWARD = "forward";

    private static final String DEFAULT_FROM = "-1h";

    private final LokiClient client;
    private final ExploreLinks exploreLinks;
    private final LokiProperties properties;

    /** searchId to snapshot. */
    private final Map<String, CachedLokiSearch> byId = new ConcurrentHashMap<>();
    /** stable-window key to searchId, enabling reuse of immutable windows. */
    private final Map<String, String> stableKeyToId = new ConcurrentHashMap<>();

    public LokiSearchCacheService(LokiClient client, ExploreLinks exploreLinks,
            LokiProperties properties) {
        this.client = client;
        this.exploreLinks = exploreLinks;
        this.properties = properties;
    }

    /**
     * Returns a snapshot for the given query and window, fetching and caching it if needed. Stable
     * windows reuse a prior identical snapshot; live windows always fetch fresh.
     *
     * @param datasourceUid Loki datasource UID (already resolved)
     * @param query         LogQL log query
     * @param from          window start in any form {@link Times} accepts; defaults to -1h
     * @param until         window end; defaults to now
     * @param direction     {@code backward}/{@code desc} (default) or {@code forward}/{@code asc}
     */
    public Handle search(Target target, String datasourceUid, String query, String from, String until,
            String direction) {
        this.sweepExpired();
        Instant now = Instant.now();
        String dir = normalizeDirection(direction);
        Window window = this.resolveWindow(from, until, now);
        // Keyed on the RESOLVED absolute instants so that relative spans ("-1h") drifting between
        // calls correctly miss rather than serve the wrong window.
        // The environment is part of the key: the same datasource UID can exist in two Grafanas.
        String stableKey = window.stable()
                ? String.join(" ", target.name(), datasourceUid, query, window.from().toString(),
                        window.until().toString(), dir)
                : null;
        CachedLokiSearch reusable = stableKey == null ? null
                : this.findFresh(stableKey, now.toEpochMilli());
        if (reusable != null) {
            return new Handle(reusable, true);
        }
        return new Handle(this.fetchAndCache(target, datasourceUid, query, dir, window, stableKey,
                now), false);
    }

    /** Looks up a cached snapshot by id, or throws if it is unknown or has expired. */
    public CachedLokiSearch get(String searchId) {
        this.sweepExpired();
        CachedLokiSearch snapshot = searchId == null ? null : this.byId.get(searchId);
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Unknown or expired searchId '" + searchId + "'. Run search_loki_logs again to "
                            + "create a fresh snapshot.");
        }
        return snapshot;
    }

    /** Accepts Loki's own vocabulary plus the {@code asc}/{@code desc} an MCP client may reach for. */
    public static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return BACKWARD;
        }
        String value = direction.trim().toLowerCase(Locale.ROOT);
        boolean forward = FORWARD.equals(value) || "asc".equals(value) || "ascending".equals(value);
        return forward ? FORWARD : BACKWARD;
    }

    private Window resolveWindow(String from, String until, Instant now) {
        Instant fromInstant = Times.resolve(from == null || from.isBlank() ? DEFAULT_FROM : from, now);
        Instant untilInstant = Times.resolve(until, now);
        if (!fromInstant.isBefore(untilInstant)) {
            throw new IllegalArgumentException("Window start (" + fromInstant + ") must be before end ("
                    + untilInstant + ").");
        }
        Instant stableThreshold = now.minus(Duration.ofMinutes(this.properties.stableWindowMinutes()));
        return new Window(fromInstant, untilInstant, untilInstant.isBefore(stableThreshold));
    }

    /** A previously cached snapshot for this stable key, or null if absent/expired. */
    private CachedLokiSearch findFresh(String stableKey, long nowMillis) {
        String existingId = this.stableKeyToId.get(stableKey);
        CachedLokiSearch existing = existingId == null ? null : this.byId.get(existingId);
        return existing != null && !existing.isExpired(nowMillis) ? existing : null;
    }

    private CachedLokiSearch fetchAndCache(Target target, String datasourceUid, String query,
            String dir, Window window, String stableKey, Instant now) {
        boolean backward = BACKWARD.equals(dir);
        FetchedLogs fetched = this.client.fetchEntries(target, datasourceUid, query,
                Times.toNanos(window.from()), Times.toNanos(window.until()), backward);
        long ttlMinutes = window.stable() ? this.properties.stableTtlMinutes() : this.properties.liveTtlMinutes();
        String id = UUID.randomUUID().toString();
        CachedLokiSearch snapshot = new CachedLokiSearch(
                id, datasourceUid, query, dir,
                window.from().toString(), window.until().toString(),
                fetched.entries(), fetched.streams(),
                fetched.capped(), window.stable(),
                this.exploreLinks.explore(target, datasourceUid, "loki",
                        Map.of("expr", query, "queryType", "range"),
                        window.from().toEpochMilli(), window.until().toEpochMilli()),
                now.toEpochMilli(),
                now.plus(Duration.ofMinutes(ttlMinutes)).toEpochMilli());
        this.byId.put(id, snapshot);
        if (stableKey != null) {
            this.stableKeyToId.put(stableKey, id);
        }
        return snapshot;
    }

    private void sweepExpired() {
        long now = Instant.now().toEpochMilli();
        this.byId.values().removeIf(snapshot -> snapshot.isExpired(now));
        this.stableKeyToId.values().removeIf(id -> !this.byId.containsKey(id));
    }

    /** A resolved time window plus whether it is immutable (so its snapshot may be reused). */
    private record Window(Instant from, Instant until, boolean stable) {
    }

    /** A snapshot plus whether this call reused an existing one (vs. fetched fresh). */
    public record Handle(CachedLokiSearch search, boolean reused) {
    }
}
