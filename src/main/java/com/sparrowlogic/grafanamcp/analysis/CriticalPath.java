package com.sparrowlogic.grafanamcp.analysis;

import com.sparrowlogic.grafanamcp.domain.ParsedTrace;
import com.sparrowlogic.grafanamcp.domain.TraceSpan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out which spans actually determined a trace's duration.
 *
 * <p>The naive approach — sort spans by duration and show the top few — is wrong in a way that
 * sends people down blind alleys: a 200 ms span that ran in parallel with a 900 ms sibling did not
 * cost the request anything, but a duration sort puts it near the top. The critical path is the
 * chain of intervals during which <em>nothing else</em> was blocking progress.
 *
 * <p>The walk starts at the root's end and moves backwards. Within the window a span occupies, the
 * child that finishes last is what the span was waiting for, so that child's interval joins the
 * path and the walk recurses into it; the gap between that child's end and the window's end is time
 * the span spent on its own work. Then the window shrinks to before that child started, and the next
 * last-finishing child is considered. Segments are accumulated per span, so a span that blocked in
 * several separate stretches is credited with all of them.
 */
public final class CriticalPath {

    private CriticalPath() {
    }

    /**
     * Nanoseconds each span spent on the critical path, keyed by span id and ordered by the time
     * the span first appears on the path.
     */
    public static Map<String, Long> of(ParsedTrace trace) {
        TraceSpan root = trace.root();
        if (root == null) {
            return Map.of();
        }
        Map<String, List<TraceSpan>> children = childrenByLatestEnd(trace);
        Map<String, Long> onPath = new LinkedHashMap<>();
        walk(root, root.startNs(), root.endNs(), children, onPath, new HashSet<>());
        return onPath;
    }

    /**
     * Accumulates the stretches of {@code [windowStart, windowEnd)} that {@code span} and its
     * descendants were responsible for.
     *
     * @param visited span ids already walked, so a malformed parent link cannot loop forever
     */
    private static void walk(TraceSpan span, long windowStart, long windowEnd,
            Map<String, List<TraceSpan>> children, Map<String, Long> onPath, Set<String> visited) {
        if (!visited.add(span.spanId())) {
            return;
        }
        long cursor = Math.min(span.endNs(), windowEnd);
        for (TraceSpan child : children.getOrDefault(span.spanId(), List.of())) {
            if (cursor <= windowStart) {
                break;
            }
            if (child.endNs() > cursor || child.endNs() <= windowStart) {
                continue;
            }
            credit(onPath, span, cursor - child.endNs());
            walk(child, Math.max(child.startNs(), windowStart), Math.min(child.endNs(), cursor),
                    children, onPath, visited);
            cursor = Math.max(windowStart, child.startNs());
        }
        credit(onPath, span, cursor - windowStart);
    }

    private static void credit(Map<String, Long> onPath, TraceSpan span, long nanos) {
        if (nanos > 0L) {
            onPath.merge(span.spanId(), nanos, Long::sum);
        }
    }

    /** Children of each span, latest-finishing first — the order the backwards walk needs. */
    private static Map<String, List<TraceSpan>> childrenByLatestEnd(ParsedTrace trace) {
        Map<String, List<TraceSpan>> children = new LinkedHashMap<>();
        trace.childrenBySpanId().forEach((parent, kids) -> {
            List<TraceSpan> sorted = new ArrayList<>(kids);
            sorted.sort(Comparator.comparingLong(TraceSpan::endNs).reversed());
            children.put(parent, List.copyOf(sorted));
        });
        return children;
    }

    /**
     * Exclusive ("self") time per span: its own duration minus the union of its children's
     * intervals. Union rather than sum, because concurrent children would otherwise subtract more
     * than the wall-clock time they actually took and drive the result negative.
     */
    public static Map<String, Long> selfTime(ParsedTrace trace) {
        Map<String, List<TraceSpan>> children = trace.childrenBySpanId();
        Map<String, Long> selfTime = new LinkedHashMap<>();
        for (TraceSpan span : trace.spans()) {
            long covered = unionLength(children.getOrDefault(span.spanId(), List.of()),
                    span.startNs(), span.endNs());
            selfTime.put(span.spanId(), Math.max(0L, span.durationNs() - covered));
        }
        return selfTime;
    }

    /** Length of the union of the given intervals, clamped to {@code [from, to)}. */
    private static long unionLength(List<TraceSpan> spans, long from, long to) {
        List<long[]> intervals = new ArrayList<>();
        for (TraceSpan span : spans) {
            long start = Math.max(span.startNs(), from);
            long end = Math.min(span.endNs(), to);
            if (end > start) {
                intervals.add(new long[] {start, end});
            }
        }
        intervals.sort(Comparator.comparingLong(interval -> interval[0]));
        long total = 0L;
        long cursor = Long.MIN_VALUE;
        for (long[] interval : intervals) {
            long start = Math.max(interval[0], cursor);
            if (interval[1] > start) {
                total += interval[1] - start;
                cursor = interval[1];
            }
        }
        return total;
    }
}
