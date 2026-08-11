package com.sparrowlogic.grafanamcp.client;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The time vocabulary every tool on this server accepts, so a caller never has to remember which
 * signal wants which format. Datasource APIs take absolute bounds (Loki nanoseconds, Prometheus
 * epoch seconds), so a caller-supplied time is resolved to an absolute {@link Instant} up front —
 * to build the request, and to decide whether a window is stable enough to cache (see
 * {@code LokiSearchCacheService}).
 *
 * <p>Accepted forms: {@code null}/blank/{@code now}; a relative span with or without a leading
 * minus ({@code -24h}, {@code 30m}, {@code 7d}, {@code 2w}), always looking backward from now;
 * Unix epoch seconds ({@code 1786385265}); a bare UTC date ({@code 2026-06-01}); or a full
 * ISO-8601 instant ({@code 2026-06-01T12:00:00Z}).
 */
public final class Times {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private Times() {
    }

    /**
     * Resolves a caller-supplied time to an absolute instant relative to {@code now}.
     *
     * @throws IllegalArgumentException if the value matches none of the accepted forms — the
     *         message lists them so an MCP client can correct the call
     */
    public static Instant resolve(String input, Instant now) {
        if (input == null || input.isBlank() || "now".equalsIgnoreCase(input.trim())) {
            return now;
        }
        String value = input.trim();
        Instant result;
        if (value.matches("-?\\d+[smhdw]")) {
            result = now.minus(parseSpan(value));
        } else if (value.matches("\\d+")) {
            result = Instant.ofEpochSecond(Long.parseLong(value));
        } else if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            result = parseBareDate(value);
        } else {
            result = parseIsoInstant(value);
        }
        return result;
    }

    /**
     * Parses a duration (a bucket width or range-vector span) to seconds. Accepts a unit suffix
     * ({@code 30s}, {@code 5m}, {@code 1h}, {@code 1d}) or bare seconds ({@code 300}).
     *
     * @throws IllegalArgumentException if the value is not a duration
     */
    public static long spanSeconds(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.matches("\\d+")) {
            return Long.parseLong(trimmed);
        }
        if (!trimmed.matches("\\d+[smhdw]")) {
            throw new IllegalArgumentException("Unrecognized duration '" + value
                    + "'. Use a span like 30s, 5m, 1h, 1d, or bare seconds.");
        }
        return parseSpan(trimmed).toSeconds();
    }

    /** Nanoseconds since the epoch — the timestamp unit Loki's query API uses. */
    public static long toNanos(Instant instant) {
        return instant.getEpochSecond() * NANOS_PER_SECOND + instant.getNano();
    }

    /** Inverse of {@link #toNanos}, for rendering a Loki entry timestamp as an instant. */
    public static Instant fromNanos(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS_PER_SECOND),
                Math.floorMod(nanos, NANOS_PER_SECOND));
    }

    /** Spans always look backward from now, so a missing leading minus is treated as present. */
    private static Duration parseSpan(String value) {
        long n = Long.parseLong(value.replaceAll("[^0-9]", ""));
        return switch (value.charAt(value.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            case 'w' -> Duration.ofDays(n * 7);
            default -> throw invalid(value);
        };
    }

    private static Instant parseBareDate(String value) {
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeException ex) {
            throw invalid(value);
        }
    }

    private static Instant parseIsoInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException ex) {
            throw invalid(value);
        }
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException("Unrecognized time '" + value
                + "'. Use a relative span (-24h, 30m, 7d), 'now', Unix epoch seconds, "
                + "a date (2026-06-01), or an ISO-8601 instant (2026-06-01T12:00:00Z).");
    }
}
