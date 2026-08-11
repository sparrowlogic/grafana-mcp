package com.sparrowlogic.grafanamcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for Loki log search, bound from {@code grafana.loki.*} properties.
 *
 * <p>Every default here exists to keep a tool response under the MCP token ceiling. Loki can
 * return tens of thousands of log lines for a one-hour window, so a search fetches at most
 * {@code maxFetch} entries into a cached snapshot and serves them {@code defaultPageSize} at a
 * time with each line truncated to {@code lineMaxChars}.
 *
 * @param datasourceUid       UID of the Loki datasource; when blank it is auto-discovered by
 *                            looking for the first datasource of type {@code loki}
 * @param maxFetch            hard cap on log entries pulled into one cached snapshot
 * @param pageLimit           entries requested per underlying Loki {@code query_range} call while
 *                            walking the window (Loki's own per-request {@code limit})
 * @param defaultPageSize     entries returned per MCP page when a tool does not specify a size
 * @param maxPageSize         upper bound on the MCP page size a tool may request
 * @param pageCharBudget      total log-line characters one page may spend; the per-line truncation
 *                            limit is this divided by the number of entries on the page, so short
 *                            lines survive intact and only a genuinely long page gets clipped
 * @param minLineChars        floor for the per-line limit, however many entries a page holds
 * @param maxLineChars        ceiling for the per-line limit, however few entries a page holds
 * @param stableWindowMinutes a window whose end is older than this many minutes is treated as
 *                            immutable ("ingestion has settled"), so its snapshot is reusable
 * @param stableTtlMinutes    cache TTL for stable (immutable) snapshots
 * @param liveTtlMinutes      cache TTL for live snapshots (end ≈ now), kept short
 * @param maxVolumeSeries     maximum series returned by a log-volume (count) query
 */
@ConfigurationProperties(prefix = "grafana.loki")
public record LokiProperties(
        String datasourceUid,
        @DefaultValue("2000") int maxFetch,
        @DefaultValue("1000") int pageLimit,
        @DefaultValue("25") int defaultPageSize,
        @DefaultValue("200") int maxPageSize,
        @DefaultValue("20000") int pageCharBudget,
        @DefaultValue("120") int minLineChars,
        @DefaultValue("2000") int maxLineChars,
        @DefaultValue("5") int stableWindowMinutes,
        @DefaultValue("30") int stableTtlMinutes,
        @DefaultValue("2") int liveTtlMinutes,
        @DefaultValue("25") int maxVolumeSeries) {
}
