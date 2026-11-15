package com.burnline.insight;

import com.burnline.insight.service.InsightServiceTestAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인사이트 카드 계산(연도별 막대, 상위 해, 월별 비중). 하위 서비스 없이 시계열 응답 모양(명세 7-3)만으로 확인한다.
 * 여러 서비스를 함께 띄운 확인은 README의 "B 파트 로컬 실행"을 따른다.
 */
class InsightCardsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode series(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void strip_keeps_same_month_2012_to_2025_without_2012_01() throws Exception {
        JsonNode s = series("""
                {"months":[{"month":"2011-03","value":5},{"month":"2012-01","value":9},{"month":"2012-03","value":1},
                 {"month":"2013-03","value":0},{"month":"2025-03","value":384},{"month":"2025-04","value":7},
                 {"month":"2026-03","value":2},{"month":"2014-03","value":null}]}""");
        List<Map<String, Object>> strip = InsightServiceTestAccess.strip(s, 3);
        assertThat(strip).extracting(x -> x.get("year")).containsExactly(2012, 2013, 2025);
    }

    @Test
    void top_years_include_this_year_and_mark_ties() {
        List<Map<String, Object>> strip = List.of(
                Map.of("year", 2018, "v", 50.0), Map.of("year", 2022, "v", 42.0), Map.of("year", 2013, "v", 28.1),
                Map.of("year", 2019, "v", 26.0), Map.of("year", 2025, "v", 6.0), Map.of("year", 2012, "v", 0.0),
                Map.of("year", 2014, "v", 0.0));
        // 고성 2019-04 (사이트 순위 카드): 4위
        var top = InsightServiceTestAccess.topYears(strip, 2019);
        assertThat(top).hasSize(5);
        assertThat(top.stream().filter(x -> x.get("year").equals(2019)).findFirst().orElseThrow().get("rank")).isEqualTo(4L);
        // 0칸 해는 공동 순위
        var low = InsightServiceTestAccess.topYears(strip, 2014);
        assertThat(low.get(low.size() - 1).get("tied")).isEqualTo(true);
    }

    @Test
    void monthly_sums_from_start_month() throws Exception {
        JsonNode s = series("""
                {"months":[{"month":"2012-01","value":9},{"month":"2012-03","value":1.5},{"month":"2013-03","value":2},
                 {"month":"2013-12","value":4}]}""");
        List<Double> mt = InsightServiceTestAccess.monthly(s, "2012-02");
        assertThat(mt).hasSize(12);
        assertThat(mt.get(0)).isZero();
        assertThat(mt.get(2)).isEqualTo(3.5);
        assertThat(mt.get(11)).isEqualTo(4.0);
    }

    @Test
    void longterm_quality_is_code_value() throws Exception {
        JsonNode lt = InsightServiceTestAccess.longterm(series("""
                {"fire_cells_2001_2025":477.3,"annual_avg":19.1,"quality":"높음"}"""));
        assertThat(lt.path("quality").asText()).isEqualTo("high");
        assertThat(lt.path("annual_avg").asDouble()).isEqualTo(19.1);
        assertThat(InsightServiceTestAccess.longterm(series("{\"quality\":\"보통\"}")).path("quality").asText()).isEqualTo("medium");
        assertThat(InsightServiceTestAccess.longterm(series("{\"quality\":\"낮음\"}")).path("quality").asText()).isEqualTo("low");
    }

    @Test
    void series_month_gives_rank_for_sparse_head() throws Exception {
        JsonNode s = series("""
                {"months":[{"month":"2025-02","value":0,"rank":null,"n":14},{"month":"2025-03","value":384,"rank":1,"n":14}]}""");
        JsonNode m = InsightServiceTestAccess.seriesMonth(s, "2025-03");
        assertThat(m.path("rank").asInt()).isEqualTo(1);
        assertThat(m.path("n").asInt()).isEqualTo(14);
        assertThat(InsightServiceTestAccess.seriesMonth(s, "2025-04")).isNull();
    }

    @Test
    void detect_bins_follow_source_order() {
        List<String> jsonbOrder = new java.util.ArrayList<>(List.of("1~10", "0.1~1", "10~100", "0.1 ha 미만", "100 ha 이상"));
        jsonbOrder.sort(java.util.Comparator.comparingInt(InsightServiceTestAccess::binOrder));
        assertThat(jsonbOrder).containsExactly("0.1 ha 미만", "0.1~1", "1~10", "10~100", "100 ha 이상");
    }
}
