package com.sparrowlogic.grafanamcp.cache;

import com.sparrowlogic.grafanamcp.config.TempoProperties;
import com.sparrowlogic.grafanamcp.domain.ParsedTrace;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Holds parsed traces for the life of an investigation.
 *
 * <p>Keyed by environment and trace id together, since the same id could exist in two Grafanas.
 *
 * <p>Simpler than the log-search cache, and for a good reason: a trace is <em>immutable</em> once
 * written. There is no live-window problem, no staleness question and no need to key on a resolved
 * time range — a trace id names exactly one document forever. So a summary followed by three span
 * listings costs one Tempo request, not four.
 */
@Service
public class TraceCacheService {

    private final TempoProperties properties;
    private final Map<String, Entry> byTraceId = new ConcurrentHashMap<>();

    public TraceCacheService(TempoProperties properties) {
        this.properties = properties;
    }

    /** Stores a parsed trace, replacing any previous parse of the same id in that environment. */
    public void put(String environment, ParsedTrace trace, String exploreUrl) {
        this.sweepExpired();
        long expiresAt = Instant.now()
                .plus(Duration.ofMinutes(this.properties.cacheTtlMinutes()))
                .toEpochMilli();
        this.byTraceId.put(key(environment, trace.traceId()), new Entry(trace, exploreUrl, expiresAt));
    }

    /** A cached parse, or null when the trace has not been fetched (or has expired). */
    public ParsedTrace get(String environment, String traceId) {
        Entry entry = this.entry(environment, traceId);
        return entry == null ? null : entry.trace();
    }

    /** The Explore link recorded alongside a cached trace, or null. */
    public String exploreUrl(String environment, String traceId) {
        Entry entry = this.entry(environment, traceId);
        return entry == null ? null : entry.exploreUrl();
    }

    private Entry entry(String environment, String traceId) {
        this.sweepExpired();
        return traceId == null ? null : this.byTraceId.get(key(environment, traceId));
    }

    /** Trace ids are only unique within one Grafana, so the environment is part of the key. */
    private static String key(String environment, String traceId) {
        return environment + '|' + traceId;
    }

    private void sweepExpired() {
        long now = Instant.now().toEpochMilli();
        this.byTraceId.values().removeIf(entry -> now >= entry.expiresAtMillis());
    }

    /** A cached trace with its Explore link and expiry. */
    private record Entry(ParsedTrace trace, String exploreUrl, long expiresAtMillis) {
    }
}
