package com.sparrowlogic.grafanamcp.domain;

import com.sparrowlogic.grafanamcp.client.Times;

/**
 * A token-bounded projection of a {@link LogEntry} returned on a search page: the line is
 * truncated and the stream's labels are replaced by a legend key resolved through the page's
 * {@code streams} map. Fetch the complete line and full label set with
 * {@code get_loki_entry(searchId, entryId)}.
 *
 * @param id     entry id — use with get_loki_entry for the full line and labels
 * @param time   entry time as an ISO-8601 UTC instant
 * @param stream legend key ({@code s0}, {@code s1}, …) into the page's {@code streams} map
 * @param line   the log line, truncated to the configured maximum
 */
public record CompactLogEntry(String id, String time, String stream, String line) {

    /** Projects a cached entry into its compact form, truncating the line to {@code maxChars}. */
    public static CompactLogEntry from(LogEntry entry, int maxChars) {
        return new CompactLogEntry(
                entry.id(),
                Times.fromNanos(entry.timestampNs()).toString(),
                streamKey(entry.streamIndex()),
                truncate(entry.line(), maxChars));
    }

    /** The legend key a given stream index is published under. */
    public static String streamKey(int streamIndex) {
        return "s" + streamIndex;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int cut = maxChars;
        // Back off one char if the cut would fall between a UTF-16 surrogate pair (e.g. an
        // emoji), which would otherwise leave a lone surrogate in the returned string.
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut) + "… [truncated — use get_loki_entry for the full line]";
    }
}
