package com.sparrowlogic.grafanamcp.analysis;

import com.sparrowlogic.grafanamcp.domain.ParsedTrace;
import com.sparrowlogic.grafanamcp.domain.TraceSpan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CriticalPathTest {

    private static final long MS = 1_000_000L;

    /** A span with a millisecond-based window, to keep the arithmetic in the tests readable. */
    private static TraceSpan span(String id, String parent, String service, long startMs, long endMs) {
        return new TraceSpan(id, parent, id, service, "scope", "SPAN_KIND_INTERNAL",
                startMs * MS, endMs * MS, "STATUS_CODE_UNSET", "", Map.of());
    }

    private static ParsedTrace trace(TraceSpan... spans) {
        return new ParsedTrace("t1", List.of(spans));
    }

    @Test
    void aSerialChainPutsEveryStepOnThePath() {
        // root 0-100 { a 0-40, b 40-100 }
        ParsedTrace trace = trace(
                span("root", null, "web", 0, 100),
                span("a", "root", "web", 0, 40),
                span("b", "root", "web", 40, 100));

        Map<String, Long> onPath = CriticalPath.of(trace);

        assertThat(onPath).containsOnlyKeys("a", "b");
        assertThat(onPath.get("a")).isEqualTo(40 * MS);
        assertThat(onPath.get("b")).isEqualTo(60 * MS);
    }

    @Test
    void aParallelSiblingThatFinishesEarlierIsNotOnThePath() {
        // The whole point: 'slow' looks bad in a duration sort but cost the request nothing,
        // because 'slower' was running the entire time and finished last.
        ParsedTrace trace = trace(
                span("root", null, "web", 0, 100),
                span("slower", "root", "db", 0, 100),
                span("slow", "root", "cache", 10, 90));

        Map<String, Long> onPath = CriticalPath.of(trace);

        assertThat(onPath).containsOnlyKeys("slower");
        assertThat(onPath.get("slower")).isEqualTo(100 * MS);
        assertThat(onPath).doesNotContainKey("slow");
    }

    @Test
    void timeTheParentSpentOnItsOwnWorkIsCreditedToTheParent() {
        // root 0-100 with one child 0-30: the remaining 70ms was the root's own work.
        ParsedTrace trace = trace(
                span("root", null, "web", 0, 100),
                span("child", "root", "db", 0, 30));

        Map<String, Long> onPath = CriticalPath.of(trace);

        assertThat(onPath.get("child")).isEqualTo(30 * MS);
        assertThat(onPath.get("root")).isEqualTo(70 * MS);
    }

    @Test
    void aSpanBlockingInSeveralStretchesIsCreditedWithAllOfThem() {
        // root 0-100 { a 10-20, b 60-70 } — the root itself accounts for 0-10, 20-60 and 70-100.
        ParsedTrace trace = trace(
                span("root", null, "web", 0, 100),
                span("a", "root", "db", 10, 20),
                span("b", "root", "db", 60, 70));

        Map<String, Long> onPath = CriticalPath.of(trace);

        assertThat(onPath.get("a")).isEqualTo(10 * MS);
        assertThat(onPath.get("b")).isEqualTo(10 * MS);
        assertThat(onPath.get("root")).isEqualTo(80 * MS);
    }

    @Test
    void theWholePathAccountsForTheRootDuration() {
        ParsedTrace trace = trace(
                span("root", null, "web", 0, 100),
                span("a", "root", "web", 0, 60),
                span("a1", "a", "db", 10, 50),
                span("b", "root", "web", 60, 95));

        long total = CriticalPath.of(trace).values().stream().mapToLong(Long::longValue).sum();

        assertThat(total).isEqualTo(100 * MS);
    }

    @Test
    void aCycleInParentLinksTerminatesInsteadOfLoopingForever() {
        TraceSpan first = span("a", "b", "web", 0, 100);
        TraceSpan second = span("b", "a", "web", 0, 100);

        assertThat(CriticalPath.of(new ParsedTrace("t1", List.of(first, second)))).isNotNull();
    }

    @Test
    void selfTimeSubtractsTheUnionOfChildrenNotTheirSum() {
        // Two children overlap: summing them would subtract 80ms from a 100ms parent and understate
        // its own work; the union is only 50ms.
        ParsedTrace trace = trace(
                span("root", null, "web", 0, 100),
                span("a", "root", "db", 10, 50),
                span("b", "root", "db", 20, 60));

        Map<String, Long> selfTime = CriticalPath.selfTime(trace);

        assertThat(selfTime.get("root")).isEqualTo(50 * MS);
        assertThat(selfTime.get("a")).isEqualTo(40 * MS);
    }

    @Test
    void selfTimeIsNeverNegativeWhenAChildOutlivesItsParent() {
        ParsedTrace trace = trace(
                span("root", null, "web", 0, 50),
                span("child", "root", "db", 0, 90));

        assertThat(CriticalPath.selfTime(trace).get("root")).isZero();
    }

    @Test
    void anEmptyTraceHasNoPath() {
        assertThat(CriticalPath.of(new ParsedTrace("t1", List.of()))).isEmpty();
    }
}
