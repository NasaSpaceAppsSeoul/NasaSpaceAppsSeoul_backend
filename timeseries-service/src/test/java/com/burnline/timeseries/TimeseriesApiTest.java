package com.burnline.timeseries;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 명세 7장 기준값 테스트. 실제 원천 파일을 임베디드 PostgreSQL에 적재한 뒤 대조한다.
 * 역할 분담 문서의 기준값: 전국 2025-03 = 1,093칸·record·6.28배 / Butte 2018-11 = 1,162칸·record·29.8배 /
 * 고성 2012-02 = 0칸·NO_DETECTION
 */
@SpringBootTest(properties = {
        "burnline.data.load=always",
        "spring.data.redis.url=redis://localhost:1"
})
@AutoConfigureMockMvc
class TimeseriesApiTest {

    private static final String UISEONG = "91817680B75376031789242";
    private static final String GOSEONG = "91817680B50485812736392";   // 강원 고성군
    private static final String BUTTE = "52423323B88547646561946";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        TestDb.register(r);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void judgement_nation_2025_03_spec_7_5() throws Exception {
        mvc.perform(get("/api/v1/KOR/judgement/KOR?month=2025-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(1093.0))
                .andExpect(jsonPath("$.chip").value("record"))
                .andExpect(jsonPath("$.judge_unit").value("self"))
                .andExpect(jsonPath("$.rank").value(1))
                .andExpect(jsonPath("$.n").value(25))
                .andExpect(jsonPath("$.rank_period[0]").value(2001))
                .andExpect(jsonPath("$.rank_period[1]").value(2025))
                .andExpect(jsonPath("$.median").value(174.0))
                .andExpect(jsonPath("$.band_low").value(95.6))
                .andExpect(jsonPath("$.band_high").value(294.8))
                .andExpect(jsonPath("$.ratio_to_median").value(6.28))
                .andExpect(jsonPath("$.ytd_sum").value(1157.0))
                // 누적 평년 = 2001~2025 해마다 1~3월 합의 중앙값 (파이프라인 calendar.json으로 계산: 315.6)
                .andExpect(jsonPath("$.ytd_median").value(315.6))
                .andExpect(jsonPath("$.ytd_ratio").value(3.67))
                .andExpect(jsonPath("$.ref_id").isEmpty())
                .andExpect(jsonPath("$.ref_chip").isEmpty())
                .andExpect(jsonPath("$.quality").value("high"))
                .andExpect(jsonPath("$.estimated").value(false))
                .andExpect(jsonPath("$.status").value("final"))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.ref").isEmpty());
    }

    @Test
    void judgement_sparse_district_borrows_province_spec_7_5() throws Exception {
        mvc.perform(get("/api/v1/KOR/judgement/" + UISEONG + "?month=2025-03"))
                .andExpect(jsonPath("$.value").value(384.0))
                .andExpect(jsonPath("$.region_type").value("sparse"))
                .andExpect(jsonPath("$.chip").isEmpty())
                .andExpect(jsonPath("$.judge_unit").value("province"))
                .andExpect(jsonPath("$.rank").isEmpty())
                .andExpect(jsonPath("$.ratio_to_median").isEmpty())
                .andExpect(jsonPath("$.band_low").isEmpty())
                .andExpect(jsonPath("$.band_high").isEmpty())
                .andExpect(jsonPath("$.median").value(0.0))   // 명세 7-8: 드문 지역도 평년은 준다
                .andExpect(jsonPath("$.quality").value("medium"))   // 명세 2장: 3월 평년 0칸 < 5 → 한 단계 낮춤 (7-5 예시는 high)
                .andExpect(jsonPath("$.ytd_ratio").isEmpty())   // ratio_to_median처럼 자체 판정할 때만
                .andExpect(jsonPath("$.ref_id").value("경북"))       // 3장 #7 평평한 필드
                .andExpect(jsonPath("$.ref_chip").value("record"))
                .andExpect(jsonPath("$.ref.id").value("경북"))
                .andExpect(jsonPath("$.ref.value").value(948.0))
                .andExpect(jsonPath("$.ref.chip").value("record"))
                .andExpect(jsonPath("$.ref.rank").value(1))
                .andExpect(jsonPath("$.ref.n").value(25))
                .andExpect(jsonPath("$.ref.ratio_to_median").value(72.64))
                .andExpect(jsonPath("$.ref.basis").value("harmonized_25y"));
    }

    @Test
    void judgement_no_detection_spec_7_5() throws Exception {
        mvc.perform(get("/api/v1/KOR/judgement/" + GOSEONG + "?month=2012-02"))
                .andExpect(jsonPath("$.value").value(0.0))
                .andExpect(jsonPath("$.reason").value("NO_DETECTION"))
                .andExpect(jsonPath("$.rank").isEmpty())
                .andExpect(jsonPath("$.ratio_to_median").isEmpty());
    }

    @Test
    void judgement_butte_camp_fire_spec_7_13() throws Exception {
        mvc.perform(get("/api/v1/CAL/judgement/" + BUTTE + "?month=2018-11"))
                .andExpect(jsonPath("$.label_ko").value("Butte"))
                .andExpect(jsonPath("$.value").value(1162.0))
                .andExpect(jsonPath("$.region_type").value("sufficient"))
                .andExpect(jsonPath("$.chip").value("record"))
                .andExpect(jsonPath("$.judge_unit").value("self"))
                .andExpect(jsonPath("$.rank").value(1))
                .andExpect(jsonPath("$.n").value(14))
                .andExpect(jsonPath("$.rank_period[0]").value(2012))
                .andExpect(jsonPath("$.rank_period[1]").value(2025))
                .andExpect(jsonPath("$.median").value(39.0))
                .andExpect(jsonPath("$.band_low").value(17.9))
                .andExpect(jsonPath("$.band_high").value(62.3))
                // 1162 / 39 = 29.795 → 명세 1장 "비율은 두 자리" = 29.79 (명세 7-13 예시는 29.8로 적혀 있음)
                .andExpect(jsonPath("$.ratio_to_median").value(29.79))
                .andExpect(jsonPath("$.quality").value("high"));
    }

    @Test
    void judgement_empty_states_spec_5() throws Exception {
        mvc.perform(get("/api/v1/KOR/judgement/" + UISEONG + "?month=2010-05"))
                .andExpect(jsonPath("$.value").isEmpty())
                .andExpect(jsonPath("$.reason").value("PRE_2012_DISTRICT"));
        mvc.perform(get("/api/v1/CAL/judgement/CAL?month=2026-11"))
                .andExpect(jsonPath("$.status").value("partial"))
                .andExpect(jsonPath("$.reason").value("IN_PROGRESS"));
    }

    @Test
    void status_nrt_after_noaa20_archive_spec_2() throws Exception {
        // NOAA-20 SP가 KOR는 2026-08, CAL은 2026-07에 끝난다 (hotspots_clean.csv src=archive). 그 뒤 달은 nrt
        mvc.perform(get("/api/v1/KOR/judgement/KOR?month=2026-08")).andExpect(jsonPath("$.status").value("final"));
        mvc.perform(get("/api/v1/KOR/judgement/KOR?month=2026-09")).andExpect(jsonPath("$.status").value("nrt"));
        mvc.perform(get("/api/v1/CAL/judgement/CAL?month=2026-08")).andExpect(jsonPath("$.status").value("nrt"));
        mvc.perform(get("/api/v1/CAL/judgement/CAL?month=2026-07")).andExpect(jsonPath("$.status").value("final"));
    }

    @Test
    void internal_judge_is_same_as_public() throws Exception {
        mvc.perform(get("/internal/judge?country=KOR&region_id=KOR&month=2025-03"))
                .andExpect(jsonPath("$.value").value(1093.0))
                .andExpect(jsonPath("$.chip").value("record"));
    }

    @Test
    void series_spec_7_3() throws Exception {
        mvc.perform(get("/api/v1/KOR/series/KOR?from=2025-01&to=2025-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("nat"))
                .andExpect(jsonPath("$.months", hasSize(3)))
                .andExpect(jsonPath("$.months[0].month").value("2025-01"))
                .andExpect(jsonPath("$.months[0].value").value(21.0))
                .andExpect(jsonPath("$.months[0].rank").value(13))
                .andExpect(jsonPath("$.months[0].ratio_to_median").value(1.0))
                .andExpect(jsonPath("$.months[1].value").value(43.0))
                .andExpect(jsonPath("$.months[1].rank").value(21))
                .andExpect(jsonPath("$.months[1].ratio_to_median").value(0.49))
                .andExpect(jsonPath("$.months[2].value").value(1093.0))
                .andExpect(jsonPath("$.months[2].source").value("NOAA-20"))
                .andExpect(jsonPath("$.months[2].quality").value("high"))
                .andExpect(jsonPath("$.months[2].estimated").value(false))
                .andExpect(jsonPath("$.months[2].status").value("final"))
                .andExpect(jsonPath("$.months[2].n").value(25));
        mvc.perform(get("/api/v1/KOR/series/" + UISEONG))
                .andExpect(jsonPath("$.level").value("dist"))
                .andExpect(jsonPath("$.months[0].value").isEmpty());   // 2012-02 전은 값 없음
    }

    @Test
    void baseline_spec_7_4() throws Exception {
        mvc.perform(get("/api/v1/KOR/baseline/KOR"))
                .andExpect(jsonPath("$.period[0]").value(2001))
                .andExpect(jsonPath("$.by_month", hasSize(12)))
                .andExpect(jsonPath("$.by_month[2].month").value(3))
                .andExpect(jsonPath("$.by_month[2].median").value(174.0))
                .andExpect(jsonPath("$.by_month[2].band_low").value(95.6))
                .andExpect(jsonPath("$.by_month[2].band_high").value(294.8))
                .andExpect(jsonPath("$.heat_bins", hasSize(3)));
        mvc.perform(get("/api/v1/KOR/baseline/" + UISEONG))
                .andExpect(jsonPath("$.period[0]").value(2012))
                .andExpect(jsonPath("$.by_month[2].band_low").isEmpty());   // 드문 지역은 띠 없음
    }

    @Test
    void export_csv_spec_7_11() throws Exception {
        byte[] body = mvc.perform(get("/api/v1/KOR/export.csv?id=KOR"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String csv = new String(body, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿region,year,month,fire_cells,median,band_low,band_high,rank,years_compared\n");
        assertThat(csv).contains("대한민국 전체,2025,03,1093,174,95.6,294.8,1,25\n");
    }

    @Test
    void export_csv_etag_spec_1() throws Exception {
        // 명세 1장: ETag = data_version, 같은 ETag로 다시 요청하면 304. 최근 달이 들어 있어 짧은 캐시
        var res = mvc.perform(get("/api/v1/KOR/export.csv?id=KOR"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("ETag"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=300")))
                .andReturn().getResponse();
        mvc.perform(get("/api/v1/KOR/export.csv?id=KOR").header("If-None-Match", res.getHeader("ETag")))
                .andExpect(status().isNotModified());
        mvc.perform(get("/api/v1/KOR/export.csv?id=NOPE").header("If-None-Match", res.getHeader("ETag")))
                .andExpect(status().isNotFound());
    }

    @Test
    void series_reversed_range_is_invalid_param() throws Exception {
        // from > to면 어떤 달이든 400: 같은 해, 해 넘김, 한 달 차이, 전체 범위, 다른 나라·구역
        String[][] reversed = {
                {"KOR", "KOR", "2025-05", "2025-01"}, {"KOR", "KOR", "2025-01", "2024-12"},
                {"KOR", "KOR", "2025-12", "2001-01"}, {"KOR", "경북", "2020-04", "2020-03"},
                {"KOR", UISEONG, "2019-01", "2018-12"}, {"CAL", "CAL", "2026-11", "2026-10"},
                {"BC", "BC", "2020-07", "2019-07"}};
        for (String[] r : reversed) {
            mvc.perform(get("/api/v1/%s/series/%s?from=%s&to=%s".formatted(r[0], r[1], r[2], r[3])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        }
        mvc.perform(get("/api/v1/KOR/series/KOR?from=2025-01&to=2025-01"))   // 같은 달은 한 달
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months", hasSize(1)));
    }

    @Test
    void errors() throws Exception {
        mvc.perform(get("/api/v1/KOR/judgement/KOR?month=1999-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONTH_OUT_OF_RANGE"));
        mvc.perform(get("/api/v1/KOR/judgement/KOR?month=2025-3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(get("/api/v1/KOR/judgement/NOPE?month=2025-03"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        mvc.perform(get("/api/v1/KOR/series/KOR?from=2030-01"))
                .andExpect(jsonPath("$.code").value("MONTH_OUT_OF_RANGE"));
    }

    /** 지도 일괄 판정은 상세 판정과 같은 규칙: 드문 구역(의성)은 chip 없이 빌려 쓴 판정(ref_chip)을 준다 */
    @Test
    void bulk_map_matches_single_judgement() throws Exception {
        String single = mvc.perform(get("/api/v1/KOR/judgement/" + UISEONG + "?month=2025-03"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode s = new ObjectMapper().readTree(single);
        mvc.perform(get("/api/v1/KOR/judgement?month=2025-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions", hasSize(229)))
                .andExpect(jsonPath("$.regions[?(@.id == '" + UISEONG + "')].chip").value(contains((Object) null)))
                .andExpect(jsonPath("$.regions[?(@.id == '" + UISEONG + "')].ref_id").value(contains(s.get("ref_id").asText())))
                .andExpect(jsonPath("$.regions[?(@.id == '" + UISEONG + "')].ref_chip").value(contains(s.get("ref_chip").asText())));
    }

    /** 남반구(칠레)는 올해 누적을 7월부터 센다: 2026-02 누적 = 2025-07 ~ 2026-02 합. 누적 범위는 아래 끝 < 위 끝 */
    @Test
    void southern_ytd_starts_in_july() throws Exception {
        String series = mvc.perform(get("/api/v1/CHL/series/CHL?from=2025-07&to=2026-02"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        double sum = 0;
        for (JsonNode m : new ObjectMapper().readTree(series).path("months")) {
            sum += m.path("value").asDouble();
        }
        String body = mvc.perform(get("/api/v1/CHL/judgement/CHL?month=2026-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ytd_sum").value(sum))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode j = new ObjectMapper().readTree(body);
        assertThat(j.path("ytd_lo").isNumber()).isTrue();
        assertThat(j.path("ytd_hi").asDouble()).isGreaterThan(j.path("ytd_lo").asDouble());
    }

    /** raw_before = 보정 전 원값 이어 붙이기 (파이프라인 calendar.json): 2012 전은 MODIS 원값, 2018-04~는 NOAA-20 = 보정값 */
    @Test
    void series_raw_before() throws Exception {
        mvc.perform(get("/api/v1/KOR/series/KOR?from=2005-04&to=2005-04"))
                .andExpect(jsonPath("$.months[0].value").value(189.8))
                .andExpect(jsonPath("$.months[0].raw_before").value(111.0));
        mvc.perform(get("/api/v1/KOR/series/KOR?from=2025-03&to=2025-03"))
                .andExpect(jsonPath("$.months[0].raw_before").value(1093.0));
        // 하위 구역: 2012-02 전은 없음
        mvc.perform(get("/api/v1/KOR/series/" + UISEONG + "?from=2011-12&to=2025-03"))
                .andExpect(jsonPath("$.months[0].raw_before").isEmpty())
                .andExpect(jsonPath("$.months[-1].raw_before").isNumber());
        // 시·도: 소속 시·군·구 raw_before 합 (경북 22곳). 2012 전도 MODIS 원값 합으로 채워짐
        mvc.perform(get("/api/v1/KOR/series/경북?from=2025-03&to=2025-03"))
                .andExpect(jsonPath("$.months[0].raw_before").value(948.0));
        mvc.perform(get("/api/v1/KOR/series/경북?from=2010-03&to=2010-03"))
                .andExpect(jsonPath("$.months[0].raw_before").value(3.0));
    }
}
