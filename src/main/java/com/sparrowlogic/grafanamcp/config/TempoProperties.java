package com.sparrowlogic.grafanamcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for Tempo trace queries, bound from {@code grafana.tempo.*}.
 *
 * <p>A production trace runs to hundreds or thousands of spans, so {@code get_trace} answers with an
 * analysis rather than the trace: these values bound that analysis and the opt-in span listing
 * behind it.
 *
 * @param datasourceUid           UID of the Tempo datasource; blank means discover by type
 * @param defaultLimit            traces returned by a search when the caller does not say
 * @param maxLimit                upper bound on the search limit a caller may request
 * @param spansPerHit             matching spans shown per search hit (Tempo's {@code spss})
 * @param defaultSpansDetail      spans returned by a span listing when the caller does not say
 * @param maxSpansDetail          hard upper bound on a span listing
 * @param maxCriticalPathSteps    entries kept in the critical-path table
 * @param maxServicesListed       services kept in the self-time and call-graph tables
 * @param attributeMaxChars       span attribute values are truncated to this length in a listing
 * @param cacheTtlMinutes         how long a parsed trace is kept; a trace is immutable once
 *                                written, so this is a pure win — pagination and drill-down never
 *                                re-fetch
 * @param logWindowPaddingSeconds padding either side of a trace's window when suggesting a log
 *                                search, because log ingestion lags span timestamps
 */
@ConfigurationProperties(prefix = "grafana.tempo")
public record TempoProperties(
        String datasourceUid,
        @DefaultValue("20") int defaultLimit,
        @DefaultValue("100") int maxLimit,
        @DefaultValue("3") int spansPerHit,
        @DefaultValue("25") int defaultSpansDetail,
        @DefaultValue("150") int maxSpansDetail,
        @DefaultValue("15") int maxCriticalPathSteps,
        @DefaultValue("25") int maxServicesListed,
        @DefaultValue("200") int attributeMaxChars,
        @DefaultValue("30") int cacheTtlMinutes,
        @DefaultValue("30") int logWindowPaddingSeconds) {
}
