package com.burnline.spatial;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 원천 파일(burnline_backend_data)을 임베디드 PostgreSQL에 적재한 뒤 명세 예시·사이트 값과 대조한다.
 * Redis는 일부러 없는 주소 → 캐시 없이도 동작해야 한다.
 */
@SpringBootTest(properties = {
        "burnline.data.load=always",
        "spring.data.redis.url=redis://localhost:1"
})
@AutoConfigureMockMvc
class SpatialApiTest {

    private static final String UISEONG = "91817680B75376031789242";
    private static final String GOSEONG = "91817680B50485812736392";
    private static final String BUTTE = "52423323B88547646561946";
    private static final String PEACE_RIVER = "BC-RD-60";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        TestDb.register(r);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.burnline.spatial.service.SpatialService service;

    @Test
    void korea_official_records_all_matched() {
        // 파이프라인 ⑰: 산림청 기록 7,628건, 시·군·구 매칭 100%
        Integer total = jdbc.queryForObject("select count(*) from spatial.official_fires where country = 'KOR'", Integer.class);
        Integer unmatched = jdbc.queryForObject("select count(*) from spatial.official_fires where country = 'KOR' and region_id is null", Integer.class);
        assertThat(total).isEqualTo(7628);
        assertThat(unmatched).isZero();
    }

    @Test
    void map_samples_big_months_spec_7_6() throws Exception {
        mvc.perform(get("/api/v1/CAL/map?month=2020-09"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.points.sensor").value("NOAA-20"))
                .andExpect(jsonPath("$.points.count").value(32941))
                .andExpect(jsonPath("$.points.shown").value(4000))
                .andExpect(jsonPath("$.points.items", hasSize(4000)))
                .andExpect(jsonPath("$.regions", hasSize(58)));
        mvc.perform(get("/api/v1/KOR/map?month=2025-03&parent=경북"))
                .andExpect(jsonPath("$.parent").value("경북"))
                .andExpect(jsonPath("$.regions[?(@.id == '" + UISEONG + "')].map_bin", hasItem(4)));
    }

    @Test
    void map_before_2012_gives_null_regions_spec_5() throws Exception {
        mvc.perform(get("/api/v1/KOR/map?month=2010-03"))
                .andExpect(jsonPath("$.points.sensor").value("MODIS"))
                .andExpect(jsonPath("$.regions", hasSize(229)))
                .andExpect(jsonPath("$.regions[0].value").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.regions[0].reason").value("PRE_2012_DISTRICT"));
    }

    @Test
    void official_fires_spec_7_13() throws Exception {
        mvc.perform(get("/api/v1/BC/official-fires?region=" + PEACE_RIVER + "&month=2023-05"))
                .andExpect(jsonPath("$.source").value("BC Wildfire Service"))
                .andExpect(jsonPath("$.count").value(29))
                .andExpect(jsonPath("$.items[?(@.place == 'Donnie Creek')].area_ha", hasItem(619072.5)))
                .andExpect(jsonPath("$.items[?(@.place == 'Donnie Creek')].cause", hasItem("번개")))
                // 면적순 (동점이면 최신 먼저): 명세 7-13 예시 순서
                .andExpect(jsonPath("$.items[0].place").value("Donnie Creek"))
                .andExpect(jsonPath("$.items[1].place").value("Stoddart Creek"));
        mvc.perform(get("/api/v1/CAL/official-fires?region=" + BUTTE + "&month=2018-11"))
                .andExpect(jsonPath("$.items[?(@.place == 'Camp')].cause", hasItem("전선")));
        // /events 범위: 뒤집히면 INVALID_PARAM, 범위 밖이면 MONTH_OUT_OF_RANGE
        mvc.perform(get("/api/v1/KOR/events?from=2025-05&to=2025-01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(get("/api/v1/KOR/events?from=2030-01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONTH_OUT_OF_RANGE"));
        // 서비스 기간은 2001-01부터 (KOR MODIS 점은 2000-11부터 있어도)
        mvc.perform(get("/api/v1/KOR/map?month=2000-12")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONTH_OUT_OF_RANGE"));
        mvc.perform(get("/api/v1/KOR/official-fires")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void events_spec_7_9() throws Exception {
        mvc.perform(get("/api/v1/KOR/events?region=" + UISEONG + "&from=2025-03&to=2025-03"))
                .andExpect(jsonPath("$.items[?(@.cells == 919)].start", hasItem("2025-03-22")));
    }

    @Test
    void month_detail_matches_site() throws Exception {
        // 의성 2025-03: 가장 넓게 탄 날 26일, 묶음 전체 919칸, 50 km 첫 감지 1일.
        // 하루 최대는 파이프라인 events_by_region(peak_day_cells)과 같은 108칸 — 칸×하루를 먼저 하나로 줄인 뒤 구역에 배정한다.
        // (지금 사이트 인사이트는 구역에 먼저 거른 뒤 줄여서 111칸으로 보인다)
        mvc.perform(get("/internal/month-detail/KOR/" + UISEONG + "?month=2025-03"))
                .andExpect(jsonPath("$.dmax").value(108))
                .andExpect(jsonPath("$.clusters.peak_day_cells").value(108))
                .andExpect(jsonPath("$.dmax_date").value("2025-03-26"))
                .andExpect(jsonPath("$.fire_days").value(7))
                .andExpect(jsonPath("$.main_cluster.cells").value(919))
                .andExpect(jsonPath("$.near50.first").value(1))
                .andExpect(jsonPath("$.official", hasSize(3)))
                .andExpect(jsonPath("$.detect_rate.since").value("2018-04"))
                .andExpect(jsonPath("$.detect_rate.window_days").value(2));
        // 고성 2019-04: 묶음 하루(4월 5일), 하루 최대 26칸 (속초와 맞닿은 경계선 위 점 하나를 어느 쪽으로 보느냐에 따라 ±1칸)
        mvc.perform(get("/internal/month-detail/KOR/" + GOSEONG + "?month=2019-04"))
                .andExpect(jsonPath("$.dmax", anyOf(is(26), is(27))))
                .andExpect(jsonPath("$.dmax_date").value("2019-04-05"))
                .andExpect(jsonPath("$.main_cluster.start").value("2019-04-05"))
                .andExpect(jsonPath("$.main_cluster.end").value("2019-04-05"));
    }

    @Test
    void detect_rate_counts_official_fires_seen_within_two_days() {
        // 의성: 2018-04 이후 기록이 있고, 2025-03-22 대형 산불은 위성이 잡았다
        var r = new java.util.LinkedHashMap<>(service.detectRate(com.burnline.common.domain.Country.KOR, UISEONG));
        assertThat((Long) r.get("n")).isPositive();
        assertThat((Long) r.get("detected")).isPositive().isLessThanOrEqualTo((Long) r.get("n"));
        assertThat((Double) r.get("rate")).isBetween(0.0, 1.0);
    }

    @Test
    void recent_months_get_short_browser_cache() throws Exception {
        // 명세 1장: 과거 달은 길게, 최근 달(partial·nrt)은 짧게 (A /series와 같은 기준)
        mvc.perform(get("/api/v1/KOR/map?month=2025-03"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=86400")));
        mvc.perform(get("/api/v1/KOR/map?month=2026-10"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=300")));
        mvc.perform(get("/internal/top/KOR?month=2026-10")).andExpect(jsonPath("$.recent").value(true));
        mvc.perform(get("/internal/top/KOR?month=2025-03")).andExpect(jsonPath("$.recent").value(false));
    }

    @Test
    void top_and_landing() throws Exception {
        mvc.perform(get("/internal/top/KOR?month=2025-03"))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].label_ko").value("경북 의성군"))
                .andExpect(jsonPath("$.items[0].value").value(384.0));
        // 시·군·구 parent: 맞닿은 지역만 (자기 자신 제외), H 순
        mvc.perform(get("/internal/top/KOR?month=2025-03&parent=" + UISEONG))
                .andExpect(jsonPath("$.items[?(@.id == '" + UISEONG + "')]", hasSize(0)))
                .andExpect(jsonPath("$.items[0].label_ko").value("안동시"))
                .andExpect(jsonPath("$.items[0].value").value(220.0));
        // 해외 나라 전체: "상위 단위 + 구역" 모두 한국어 (상위 단위 ID "Attikis" 등이 섞이지 않음)
        mvc.perform(get("/internal/top/GRC?month=2023-08"))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[*].label_ko", everyItem(matchesPattern("^[^A-Za-z]+$"))));
        mvc.perform(get("/internal/landing/BC"))
                .andExpect(jsonPath("$.official_area_by_year.2023").value(2896225))
                .andExpect(jsonPath("$.years", hasSize(25)));
    }

    @Test
    void errors() throws Exception {
        mvc.perform(get("/api/v1/XX/map?month=2025-03")).andExpect(jsonPath("$.code").value("COUNTRY_NOT_READY"));
        mvc.perform(get("/api/v1/KOR/map?month=1990-01")).andExpect(jsonPath("$.code").value("MONTH_OUT_OF_RANGE"));
        mvc.perform(get("/api/v1/KOR/map?month=2025-3")).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(get("/internal/month-detail/KOR/NOPE?month=2025-03")).andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
    }
}
