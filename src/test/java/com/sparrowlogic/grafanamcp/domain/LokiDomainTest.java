package com.sparrowlogic.grafanamcp.domain;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LokiDomainTest {

    private static final long BASE_NS = 1786385265495844823L;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void compactEntryKeepsShortLinesIntact() {
        CompactLogEntry compact = CompactLogEntry.from(new LogEntry("e0", BASE_NS, 3, "short"), 100);

        assertThat(compact.id()).isEqualTo("e0");
        assertThat(compact.line()).isEqualTo("short");
        assertThat(compact.stream()).isEqualTo("s3");
        assertThat(compact.time()).isEqualTo("2026-08-10T18:07:45.495844823Z");
    }

    @Test
    void compactEntryTruncatesLongLinesAndPointsAtTheFullOne() {
        CompactLogEntry compact = CompactLogEntry.from(
                new LogEntry("e1", BASE_NS, 0, "abcdefghij"), 4);

        assertThat(compact.line()).startsWith("abcd").contains("use get_loki_entry");
    }

    @Test
    void compactEntryNeverSplitsASurrogatePair() {
        // The cut lands between the two halves of the emoji, so it backs off a character.
        CompactLogEntry compact = CompactLogEntry.from(
                new LogEntry("e2", BASE_NS, 0, "ab😀cd"), 3);

        assertThat(compact.line()).startsWith("ab").doesNotContain("\uD83D");
        assertThat(Character.isHighSurrogate(compact.line().charAt(1))).isFalse();
    }

    @Test
    void compactEntryToleratesANullLine() {
        assertThat(CompactLogEntry.from(new LogEntry("e3", BASE_NS, 0, null), 10).line()).isNull();
    }

    @Test
    void queryResponseBindsStreamsAndDropsTheStatsBlob() {
        LokiQueryResponse response = MAPPER.readValue("""
                {"status":"success","data":{"resultType":"streams","result":[
                  {"stream":{"app":"a"},"values":[["1","one"],["2","two",{"trace":"abc"}]]}
                ],"stats":{"summary":{"execTime":0.1},"querier":{"store":{"chunk":{}}}}}}
                """, LokiQueryResponse.class);

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.data().resultType()).isEqualTo("streams");
        LokiQueryResponse.Stream stream = response.data().result().getFirst();
        assertThat(stream.stream()).isEqualTo(Map.of("app", "a"));
        // A third, structured-metadata element must not break binding.
        assertThat(stream.values()).hasSize(2);
        assertThat(stream.values().get(1).path(1).asString()).isEqualTo("two");
    }

    @Test
    void volumeResultSortsByTotalAndReportsTruncation() {
        JsonNode matrix = MAPPER.readTree("""
                {"data":{"resultType":"matrix","result":[
                  {"metric":{"svc":"low"},"values":[[100,"1"]]},
                  {"metric":{"svc":"high"},"values":[[100,"7"],[200,"3"]]},
                  {"metric":{"svc":"mid"},"values":[[100,"5"]]}
                ]}}""");

        LogVolumeResult result = LogVolumeResult.from(matrix, "sum(...)", "60s",
                "2026-08-10T15:00:00Z", "2026-08-10T16:00:00Z", 2, "https://grafana.test/goto/abc");

        assertThat(result.total()).isEqualTo(16L);
        assertThat(result.seriesTotal()).isEqualTo(3);
        assertThat(result.seriesReturned()).isEqualTo(2);
        assertThat(result.seriesTruncated()).isTrue();
        assertThat(result.series()).extracting(series -> series.labels().get("svc"))
                .containsExactly("high", "mid");
        assertThat(result.series().getFirst().total()).isEqualTo(10L);
        assertThat(result.series().getFirst().points())
                .containsExactly(List.of(100L, 7L), List.of(200L, 3L));
        assertThat(result.query()).isEqualTo("sum(...)");
        assertThat(result.step()).isEqualTo("60s");
    }

    @Test
    void volumeResultHandlesAnEmptyOrAbsentMatrix() {
        LogVolumeResult empty = LogVolumeResult.from(MAPPER.readTree("{\"data\":{\"result\":[]}}"),
                "q", "60s", "from", "until", 25, "url");
        assertThat(empty.total()).isZero();
        assertThat(empty.series()).isEmpty();

        LogVolumeResult missing = LogVolumeResult.from(null, "q", "60s", "from", "until", 25, "url");
        assertThat(missing.series()).isEmpty();
        assertThat(missing.seriesTruncated()).isFalse();
    }
}
