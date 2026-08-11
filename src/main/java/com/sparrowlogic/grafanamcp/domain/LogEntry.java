package com.sparrowlogic.grafanamcp.domain;

/**
 * One log line held in a cached search snapshot, with its complete (untruncated) text.
 *
 * <p>The entry does not carry its stream's labels: identical label sets repeat across thousands of
 * entries, so the snapshot stores each distinct label set once and entries reference it by
 * {@code streamIndex}. Pages emit a per-page legend of the referenced streams
 * ({@code s0}, {@code s1}, …) instead of repeating labels on every line, which is where most of
 * the token saving in a log page comes from.
 *
 * @param id          snapshot-local id ({@code e0}, {@code e1}, …) — pass to get_loki_entry
 * @param timestampNs entry time in nanoseconds since the epoch, as Loki reports it
 * @param streamIndex index into the snapshot's list of distinct label sets
 * @param line        the complete log line
 */
public record LogEntry(String id, long timestampNs, int streamIndex, String line) {
}
