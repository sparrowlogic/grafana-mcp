package com.sparrowlogic.grafanamcp.analysis;

import com.sparrowlogic.grafanamcp.config.TempoProperties;
import com.sparrowlogic.grafanamcp.domain.ParsedTrace;
import com.sparrowlogic.grafanamcp.domain.TraceSpan;
import com.sparrowlogic.grafanamcp.domain.TraceSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceAnalyzerTest {

    private static final long MS = 1_000_000L;
    private static final long BASE = 1786389004000000000L;

    private final TraceAnalyzer analyzer = new TraceAnalyzer(
            new TempoProperties(null, 20, 100, 3, 25, 150, 15, 25, 200, 30, 30));

    private static TraceSpan span(String id, String parent, String service, String name,
            long startMs, long endMs, boolean failed, Map<String, String> attributes) {
        return new TraceSpan(id, parent, name, service, "scope",
                parent == null ? "SPAN_KIND_SERVER" : "SPAN_KIND_CLIENT",
                BASE + startMs * MS, BASE + endMs * MS,
                failed ? "STATUS_CODE_ERROR" : "STATUS_CODE_UNSET",
                failed ? "boom" : "", attributes);
    }

    /**
     * web(0-102) → api(5-95) → db(10-90, failed), plus a cache call at 20-23 that runs entirely
     * inside the database call's window — so it is genuinely concurrent and cost the request nothing.
     */
    private static ParsedTrace trace() {
        return new ParsedTrace("t1", List.of(
                span("s1", null, "web", "POST /checkout", 0, 102, false,
                        Map.of("service_name", "web", "project", "storefront")),
                span("s2", "s1", "api", "POST /orders", 5, 95, false, Map.of()),
                span("s3", "s2", "db", "INSERT orders", 10, 90, true,
                        Map.of("db.query.text", "INSERT INTO orders", "exception.type", "PgError")),
                span("s4", "s2", "cache", "GET session", 20, 23, false, Map.of())));
    }

    @Test
    void summaryAnswersTheBasics() {
        TraceSummary summary = this.analyzer.summarize(trace(), "https://grafana.test/goto/abc");

        assertThat(summary.traceId()).isEqualTo("t1");
        assertThat(summary.rootService()).isEqualTo("web");
        assertThat(summary.rootName()).isEqualTo("POST /checkout");
        assertThat(summary.durationMs()).isEqualTo(102d);
        assertThat(summary.spanCount()).isEqualTo(4);
        assertThat(summary.serviceCount()).isEqualTo(4);
        assertThat(summary.errorCount()).isEqualTo(1);
        assertThat(summary.grafanaExploreUrl()).isEqualTo("https://grafana.test/goto/abc");
    }

    @Test
    void criticalPathFollowsTheBlockingChainAndSkipsTheParallelCall() {
        TraceSummary summary = this.analyzer.summarize(trace(), "url");

        assertThat(summary.criticalPath()).extracting(TraceSummary.CriticalPathStep::name)
                .containsExactly("POST /checkout", "POST /orders", "INSERT orders");
        // The 3ms cache call ran while the database call was in flight, so it cost nothing — a
        // sort by duration would still have listed it.
        assertThat(summary.criticalPath()).extracting(TraceSummary.CriticalPathStep::name)
                .doesNotContain("GET session");
        assertThat(summary.criticalPath()).extracting(TraceSummary.CriticalPathStep::percentOfTrace)
                .allSatisfy(percent -> assertThat(percent).isGreaterThan(0d));
        double onPath = summary.criticalPath().stream()
                .mapToDouble(TraceSummary.CriticalPathStep::msOnPath).sum();
        assertThat(onPath).isEqualTo(102d);
    }

    @Test
    void serviceTimeRanksOnCriticalPathAndAlsoReportsSelfTime() {
        TraceSummary summary = this.analyzer.summarize(trace(), "url");

        // db holds 80ms of its own; web 12ms around api; api 10ms either side of db (the cache
        // call sits inside db's window, so it subtracts nothing further).
        assertThat(summary.serviceSelfTime()).extracting(TraceSummary.ServiceTime::service)
                .containsExactly("db", "web", "api", "cache");
        assertThat(summary.serviceSelfTime()).extracting(TraceSummary.ServiceTime::selfMs)
                .containsExactly(80d, 12d, 10d, 3d);
        // Critical-path attribution partitions the duration, so it sums to exactly the trace length
        // and the cache call — concurrent with db — gets nothing.
        assertThat(summary.serviceSelfTime()).extracting(TraceSummary.ServiceTime::criticalPathMs)
                .containsExactly(80d, 12d, 10d, 0d);
        assertThat(summary.serviceSelfTime().stream()
                .mapToDouble(TraceSummary.ServiceTime::criticalPathMs).sum()).isEqualTo(102d);
        assertThat(summary.serviceSelfTime().getFirst().percentOfTrace()).isEqualTo(78.4d);
        assertThat(summary.serviceSelfTime().getFirst().errorCount()).isEqualTo(1);
    }

    @Test
    void overlappingSiblingsMakeSelfTimeExceedTheTraceAndTheNoticeSaysSo() {
        // Express-style instrumentation: two middleware spans each cover almost the whole request,
        // recorded as siblings. Summing selfMs would claim ~190ms of work in a 100ms request.
        ParsedTrace overlapping = new ParsedTrace("t3", List.of(
                span("s1", null, "web", "GET /", 0, 100, false, Map.of()),
                span("s2", "s1", "web", "middleware - a", 1, 96, false, Map.of()),
                span("s3", "s1", "web", "middleware - b", 2, 95, false, Map.of())));

        TraceSummary summary = this.analyzer.summarize(overlapping, "url");

        double selfTotal = summary.serviceSelfTime().stream()
                .mapToDouble(TraceSummary.ServiceTime::selfMs).sum();
        double pathTotal = summary.serviceSelfTime().stream()
                .mapToDouble(TraceSummary.ServiceTime::criticalPathMs).sum();
        assertThat(selfTotal).isGreaterThan(summary.durationMs());
        assertThat(pathTotal).isEqualTo(summary.durationMs());
        assertThat(summary.notice()).contains("criticalPathMs");
    }

    @Test
    void onlyTheOriginOfAFailureIsReported() {
        // api and web also carry the error status, as instrumentation usually propagates it.
        ParsedTrace propagated = new ParsedTrace("t2", List.of(
                span("s1", null, "web", "POST /checkout", 0, 100, true, Map.of()),
                span("s2", "s1", "api", "POST /orders", 5, 95, true, Map.of()),
                span("s3", "s2", "db", "INSERT orders", 10, 90, true,
                        Map.of("exception.type", "PgError", "exception.message", "deadlock"))));

        TraceSummary summary = this.analyzer.summarize(propagated, "url");

        assertThat(summary.errorCount()).isEqualTo(3);
        assertThat(summary.errorOrigins()).hasSize(1);
        TraceSummary.ErrorSpan origin = summary.errorOrigins().getFirst();
        assertThat(origin.service()).isEqualTo("db");
        assertThat(origin.detail()).containsEntry("exception.type", "PgError")
                .containsEntry("exception.message", "deadlock");
        assertThat(summary.notice()).contains("only the origins are listed");
    }

    @Test
    void serviceCallGraphRecordsCrossServiceEdgesOnly() {
        TraceSummary summary = this.analyzer.summarize(trace(), "url");

        assertThat(summary.serviceCalls()).extracting(TraceSummary.CallEdge::from,
                        TraceSummary.CallEdge::to)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("web", "api"),
                        org.assertj.core.groups.Tuple.tuple("api", "db"),
                        org.assertj.core.groups.Tuple.tuple("api", "cache"));
        assertThat(summary.serviceCalls()).filteredOn(edge -> "db".equals(edge.to()))
                .allSatisfy(edge -> assertThat(edge.errors()).isEqualTo(1));
    }

    @Test
    void logSearchHintPadsTheWindowAndUsesSpanAttributesAsLabels() {
        TraceSummary summary = this.analyzer.summarize(trace(), "url");

        TraceSummary.LogSearchHint hint = summary.logSearch();
        assertThat(hint.spanLabels()).containsEntry("service_name", "web")
                .containsEntry("project", "storefront");
        assertThat(hint.suggestedQueries()).contains("{service_name=\"web\"} |= `t1`",
                "{service_name=\"web\"}");
        // 30s of padding either side, because log ingestion lags spans.
        assertThat(hint.from()).isEqualTo("2026-08-10T19:09:34Z");
        assertThat(hint.until()).isEqualTo("2026-08-10T19:10:34.102Z");
        assertThat(hint.note()).contains("search_loki_logs");
    }

    @Test
    void anEmptyTraceExplainsTheLikelyCause() {
        TraceSummary summary = this.analyzer.summarize(new ParsedTrace("t9", List.of()), "url");

        assertThat(summary.spanCount()).isZero();
        assertThat(summary.criticalPath()).isEmpty();
        assertThat(summary.logSearch()).isNull();
        assertThat(summary.notice()).contains("only searches the blocks covering");
    }
}
