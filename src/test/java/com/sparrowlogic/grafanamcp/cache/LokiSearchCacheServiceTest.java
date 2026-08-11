package com.sparrowlogic.grafanamcp.cache;

import com.sparrowlogic.grafanamcp.client.ExploreLinks;
import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.client.LokiClient;
import com.sparrowlogic.grafanamcp.config.LokiProperties;
import com.sparrowlogic.grafanamcp.domain.FetchedLogs;
import com.sparrowlogic.grafanamcp.domain.LogEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LokiSearchCacheServiceTest {

    private static final String UID = "loki-uid";
    private static final String QUERY = "{app=\"a\"}";
    private static final GrafanaEnvironments.Target TARGET =
            new GrafanaEnvironments.Target("production", "https://grafana.test", null);

    private final LokiClient client = mock(LokiClient.class);
    private final ExploreLinks exploreLinks = mock(ExploreLinks.class);

    @BeforeEach
    void setUp() {
        when(this.client.fetchEntries(any(), anyString(), anyString(), anyLong(), anyLong(), anyBoolean()))
                .thenReturn(new FetchedLogs(
                        List.of(new LogEntry("e0", 1786385265495844823L, 0, "line")),
                        List.of(Map.of("app", "a")), false));
        when(this.exploreLinks.explore(any(), anyString(), anyString(), anyMap(), anyLong(), anyLong()))
                .thenReturn("https://grafana.test/goto/abc");
    }

    private LokiSearchCacheService service(int stableTtlMinutes, int liveTtlMinutes) {
        return new LokiSearchCacheService(this.client, this.exploreLinks,
                new LokiProperties(null, 2000, 1000, 25, 200, 20000, 120, 2000, 5,
                        stableTtlMinutes, liveTtlMinutes, 25));
    }

    @Test
    void liveWindowsAreNeverReused() {
        LokiSearchCacheService service = this.service(30, 2);

        LokiSearchCacheService.Handle first = service.search(TARGET, UID, QUERY, "-10m", "now", null);
        LokiSearchCacheService.Handle second = service.search(TARGET, UID, QUERY, "-10m", "now", null);

        assertThat(first.reused()).isFalse();
        assertThat(second.reused()).isFalse();
        assertThat(first.search().searchId()).isNotEqualTo(second.search().searchId());
        verify(this.client, times(2))
                .fetchEntries(any(), anyString(), anyString(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void stableWindowsAreFetchedOnceAndReused() {
        LokiSearchCacheService service = this.service(30, 2);

        LokiSearchCacheService.Handle first = service.search(TARGET, UID, QUERY,
                "2026-06-01T00:00:00Z", "2026-06-01T01:00:00Z", null);
        LokiSearchCacheService.Handle second = service.search(TARGET, UID, QUERY,
                "2026-06-01T00:00:00Z", "2026-06-01T01:00:00Z", null);

        assertThat(first.reused()).isFalse();
        assertThat(second.reused()).isTrue();
        assertThat(second.search().searchId()).isEqualTo(first.search().searchId());
        assertThat(first.search().windowStable()).isTrue();
        verify(this.client, times(1))
                .fetchEntries(any(), anyString(), anyString(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void aRelativeWindowIsNotReusedEvenWhenStable() {
        LokiSearchCacheService service = this.service(30, 2);

        // "-3h" resolves to a different instant on each call, so reuse would serve a window the
        // caller did not ask for. Missing here is the correct, if less thrifty, behaviour.
        LokiSearchCacheService.Handle first = service.search(TARGET, UID, QUERY, "-3h", "-2h", null);
        LokiSearchCacheService.Handle second = service.search(TARGET, UID, QUERY, "-3h", "-2h", null);

        assertThat(first.search().windowStable()).isTrue();
        assertThat(second.reused()).isFalse();
        verify(this.client, times(2))
                .fetchEntries(any(), anyString(), anyString(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void aDifferentQueryDoesNotReuseTheSnapshot() {
        LokiSearchCacheService service = this.service(30, 2);

        service.search(TARGET, UID, QUERY, "2026-06-01T00:00:00Z", "2026-06-01T01:00:00Z", null);
        LokiSearchCacheService.Handle other = service.search(TARGET, UID, "{app=\"b\"}",
                "2026-06-01T00:00:00Z", "2026-06-01T01:00:00Z", null);

        assertThat(other.reused()).isFalse();
        verify(this.client, times(2))
                .fetchEntries(any(), anyString(), anyString(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void snapshotCarriesResolvedWindowAndExploreLink() {
        CachedLokiSearch snapshot = this.service(30, 2)
                .search(TARGET, UID, QUERY, "2026-06-01T00:00:00Z", "2026-06-01T01:00:00Z", null).search();

        assertThat(snapshot.fromResolved()).isEqualTo("2026-06-01T00:00:00Z");
        assertThat(snapshot.untilResolved()).isEqualTo("2026-06-01T01:00:00Z");
        assertThat(snapshot.exploreUrl()).isEqualTo("https://grafana.test/goto/abc");
        assertThat(snapshot.datasourceUid()).isEqualTo(UID);
        assertThat(snapshot.oldestNs()).isEqualTo(1786385265495844823L);
        assertThat(snapshot.newestNs()).isEqualTo(1786385265495844823L);
    }

    @Test
    void emptySnapshotHasNoCoverageTimestamps() {
        when(this.client.fetchEntries(any(), anyString(), anyString(), anyLong(), anyLong(), anyBoolean()))
                .thenReturn(new FetchedLogs(List.of(), List.of(), false));

        CachedLokiSearch snapshot = this.service(30, 2).search(TARGET, UID, QUERY, "-3h", "-2h", null).search();

        assertThat(snapshot.oldestNs()).isZero();
        assertThat(snapshot.newestNs()).isZero();
    }

    @Test
    void directionIsNormalizedAndPassedToTheClient() {
        LokiSearchCacheService service = this.service(30, 2);

        assertThat(service.search(TARGET, UID, QUERY, "-3h", "-2h", "asc").search().direction())
                .isEqualTo(LokiSearchCacheService.FORWARD);
        verify(this.client).fetchEntries(any(), anyString(), anyString(), anyLong(), anyLong(), eq(false));

        assertThat(service.search(TARGET, UID, QUERY, "-5h", "-4h", "nonsense").search().direction())
                .isEqualTo(LokiSearchCacheService.BACKWARD);
        verify(this.client).fetchEntries(any(), anyString(), anyString(), anyLong(), anyLong(), eq(true));
    }

    @Test
    void normalizeDirectionAcceptsBothVocabularies() {
        assertThat(LokiSearchCacheService.normalizeDirection(null)).isEqualTo("backward");
        assertThat(LokiSearchCacheService.normalizeDirection("  ")).isEqualTo("backward");
        assertThat(LokiSearchCacheService.normalizeDirection("FORWARD")).isEqualTo("forward");
        assertThat(LokiSearchCacheService.normalizeDirection("asc")).isEqualTo("forward");
        assertThat(LokiSearchCacheService.normalizeDirection("ascending")).isEqualTo("forward");
        assertThat(LokiSearchCacheService.normalizeDirection("desc")).isEqualTo("backward");
    }

    @Test
    void unknownSearchIdIsAnActionableFailure() {
        LokiSearchCacheService service = this.service(30, 2);

        assertThatThrownBy(() -> service.get("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("search_loki_logs again");
        assertThatThrownBy(() -> service.get(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotIsRetrievableUntilItExpires() {
        LokiSearchCacheService live = this.service(30, 2);
        String id = live.search(TARGET, UID, QUERY, "-10m", "now", null).search().searchId();
        assertThat(live.get(id).searchId()).isEqualTo(id);

        LokiSearchCacheService expiring = this.service(0, 0);
        String expiredId = expiring.search(TARGET, UID, QUERY, "-10m", "now", null).search().searchId();
        assertThatThrownBy(() -> expiring.get(expiredId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void windowMustRunForwards() {
        LokiSearchCacheService service = this.service(30, 2);

        assertThatThrownBy(() -> service.search(TARGET, UID, QUERY, "now", "-1h", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be before end");
    }
}
