package com.sparrowlogic.grafanamcp.domain;

import java.util.Map;

/**
 * The complete payload of a single log entry, returned by {@code get_loki_entry}: the full,
 * untruncated line plus the stream's full label set (which pages abbreviate to a legend key).
 *
 * @param id          the entry id it was looked up by
 * @param time        entry time as an ISO-8601 UTC instant
 * @param timestampNs entry time in nanoseconds since the epoch, as Loki reports it
 * @param labels      the complete label set of the stream this entry belongs to
 * @param line        the complete log line
 */
public record LogEntryDetail(String id, String time, long timestampNs,
        Map<String, String> labels, String line) {
}
