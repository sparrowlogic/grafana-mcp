package com.sparrowlogic.grafanamcp.domain;

import java.util.List;
import java.util.Map;

/**
 * The raw material of a search snapshot: every log entry pulled from Loki for one query and
 * window, in the requested direction, plus the distinct stream label sets those entries reference.
 *
 * @param entries entries in query order (newest first for {@code backward}), with ids assigned
 * @param streams distinct label sets; {@link LogEntry#streamIndex()} indexes into this list
 * @param capped  true if the fetch cap was reached while Loki still had more entries — the
 *                entries then cover only part of the requested window (the newest part for
 *                {@code backward}, the oldest for {@code forward})
 */
public record FetchedLogs(List<LogEntry> entries, List<Map<String, String>> streams, boolean capped) {
}
