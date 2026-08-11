package com.sparrowlogic.grafanamcp.client;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimesTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void blankAndNowResolveToNow() {
        assertThat(Times.resolve(null, NOW)).isEqualTo(NOW);
        assertThat(Times.resolve("  ", NOW)).isEqualTo(NOW);
        assertThat(Times.resolve("now", NOW)).isEqualTo(NOW);
        assertThat(Times.resolve("NOW", NOW)).isEqualTo(NOW);
    }

    @Test
    void relativeSpansLookBackwardWithOrWithoutSign() {
        assertThat(Times.resolve("-30m", NOW)).isEqualTo(Instant.parse("2026-08-10T11:30:00Z"));
        assertThat(Times.resolve("2h", NOW)).isEqualTo(Instant.parse("2026-08-10T10:00:00Z"));
        assertThat(Times.resolve("-90s", NOW)).isEqualTo(Instant.parse("2026-08-10T11:58:30Z"));
        assertThat(Times.resolve("-2d", NOW)).isEqualTo(Instant.parse("2026-08-08T12:00:00Z"));
        assertThat(Times.resolve("-1w", NOW)).isEqualTo(Instant.parse("2026-08-03T12:00:00Z"));
    }

    @Test
    void absoluteFormsParse() {
        assertThat(Times.resolve("2026-06-01", NOW)).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(Times.resolve("2026-06-01T08:30:00Z", NOW))
                .isEqualTo(Instant.parse("2026-06-01T08:30:00Z"));
        assertThat(Times.resolve("1786385265", NOW)).isEqualTo(Instant.ofEpochSecond(1786385265L));
    }

    @Test
    void unparseableTimeIsRejectedWithGuidance() {
        assertThatThrownBy(() -> Times.resolve("last tuesday", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized time")
                .hasMessageContaining("-24h");
        assertThatThrownBy(() -> Times.resolve("2026-13-01", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nanosecondsRoundTrip() {
        Instant instant = Instant.parse("2026-08-10T18:07:45.495844823Z");
        long nanos = Times.toNanos(instant);
        assertThat(nanos).isEqualTo(1786385265495844823L);
        assertThat(Times.fromNanos(nanos)).isEqualTo(instant);
    }

    @Test
    void spanSecondsAcceptsUnitsAndBareSeconds() {
        assertThat(Times.spanSeconds("300")).isEqualTo(300L);
        assertThat(Times.spanSeconds("5m")).isEqualTo(300L);
        assertThat(Times.spanSeconds("1h")).isEqualTo(3600L);
        assertThat(Times.spanSeconds(" 2d ")).isEqualTo(172800L);
        assertThatThrownBy(() -> Times.spanSeconds("-5m"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized duration");
        assertThatThrownBy(() -> Times.spanSeconds(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
