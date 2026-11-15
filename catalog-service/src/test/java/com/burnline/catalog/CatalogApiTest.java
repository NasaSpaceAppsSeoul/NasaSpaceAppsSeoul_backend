package com.burnline.catalog;

import com.burnline.common.domain.Country;
import com.burnline.common.load.SourceFiles;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 원천 파일(burnline_backend_data)을 임베디드 PostgreSQL에 적재한 뒤 명세 예시와 대조한다.
 * Redis는 일부러 없는 주소 → 캐시 없이도 동작해야 한다.
 */
@SpringBootTest(properties = {
        "burnline.data.load=always",
        "spring.data.redis.url=redis://localhost:1"
})
@AutoConfigureMockMvc
class CatalogApiTest {

    private static final String UISEONG = "91817680B75376031789242";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        TestDb.register(r);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    SourceFiles files;

    /** 자료를 새로 받을 때마다 바뀌는 값이라 숫자로 박지 않고 meta.json(NOAA-20 마지막 날)에서 읽는다 */
    private String dataEnd(Country c) {
        return files.output(c, "meta.json").path("data_range").path("noaa20").path(1).asText();
    }

    @Test
    void meta_matches_spec_7_1() throws Exception {
        mvc.perform(get("/api/v1/KOR/meta"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.country").value("KOR"))
                .andExpect(jsonPath("$.data_end").value(dataEnd(Country.KOR)))
                .andExpect(jsonPath("$.judgement_params.min_annual").value(11.1))
                .andExpect(jsonPath("$.judgement_params.risk_share").value(0.5))
                .andExpect(jsonPath("$.calibration.modis_to_snpp_by_month.3").value(1.721))
                .andExpect(jsonPath("$.validation.break_test_ratio").value(0.99))
                .andExpect(jsonPath("$.labels.lv2").value("시·군·구"))
                // 7-1 sources는 라이선스 이름만, 6장 시간대·외부 검증 자료
                .andExpect(jsonPath("$.sources[1]").value("geoBoundaries (CC BY 3.0)"))
                .andExpect(jsonPath("$.labels.boundary").value("geoBoundaries (CC BY 3.0) + 영광군 보강"))
                .andExpect(jsonPath("$.time_basis.code").value("UTC+9"))
                // 7-1 generated_at은 시각 포함 (meta.json 수정 시각, 한국 시간)
                .andExpect(jsonPath("$.generated_at").value(org.hamcrest.Matchers.matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?\\+09:00")))
                .andExpect(jsonPath("$.external_validation.available").value(true))
                .andExpect(jsonPath("$.external_validation.records").value(7628));
        mvc.perform(get("/api/v1/BC/meta"))
                .andExpect(jsonPath("$.time_basis.code").value("solar"))
                .andExpect(jsonPath("$.external_validation.records").value(42170));
    }

    @Test
    void cal_meta_labels_spec_7_13() throws Exception {
        mvc.perform(get("/api/v1/CAL/meta"))
                .andExpect(jsonPath("$.data_end").value(dataEnd(Country.CAL)))
                .andExpect(jsonPath("$.labels.single").value(true))
                .andExpect(jsonPath("$.labels.bins[1]").value(8))
                .andExpect(jsonPath("$.validation.official_break.before").value(2.54))
                .andExpect(jsonPath("$.validation.official_break.after").value(1.25))
                .andExpect(jsonPath("$.validation.official_break.inconclusive").value(false));
        mvc.perform(get("/api/v1/KOR/meta"))
                .andExpect(jsonPath("$.validation.official_break.before").value(2.67))
                .andExpect(jsonPath("$.validation.official_break.after").value(1.04))
                .andExpect(jsonPath("$.filters").isArray());
        mvc.perform(get("/api/v1/BC/meta"))
                .andExpect(jsonPath("$.validation.official_break.inconclusive").value(true));
    }

    /** 나라 전체 "평소 화재 집중 시기" 카드: 위험 시기 달·비중·정점 달 (meta.json regions.national_risk와 같은 값) */
    @Test
    void national_risk_with_peak_month() throws Exception {
        for (Country c : Country.values()) {
            JsonNode risk = files.output(c, "meta.json").path("regions").path("national_risk");
            mvc.perform(get("/api/v1/" + c.name() + "/regions/" + c.nationalId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.risk_months.length()").value(risk.path("months").size()))
                    .andExpect(jsonPath("$.risk_months[0]").value(risk.path("months").get(0).asInt()))
                    .andExpect(jsonPath("$.peak_month").value(risk.path("peak_month").asInt()));
        }
        mvc.perform(get("/api/v1/KOR/regions/KOR"))   // 한국: 2·3·4월, 정점 3월
                .andExpect(jsonPath("$.risk_months[1]").value(3))
                .andExpect(jsonPath("$.peak_month").value(3));
    }

    /** 새 세 지역: 칠레·그리스는 상위 단위(주·광역)가 있고, NSW는 주 하나. 날짜는 모두 태양시, 남반구는 시즌 7월 시작 */
    @Test
    void new_regions_labels() throws Exception {
        mvc.perform(get("/api/v1/CHL/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels.single").value(false))
                .andExpect(jsonPath("$.labels.lv2").value("시군"))
                .andExpect(jsonPath("$.labels.season_start").value(7))
                .andExpect(jsonPath("$.time_basis.code").value("solar"));
        mvc.perform(get("/api/v1/GRC/meta"))
                .andExpect(jsonPath("$.labels.single").value(false))
                .andExpect(jsonPath("$.labels.bins[2]").value(3))
                .andExpect(jsonPath("$.time_basis.code").value("solar"))
                .andExpect(jsonPath("$.validation.official_break.inconclusive").value(true));
        mvc.perform(get("/api/v1/NSW/meta"))
                .andExpect(jsonPath("$.labels.single").value(true))
                .andExpect(jsonPath("$.labels.season_start").value(7))
                .andExpect(jsonPath("$.time_basis.code").value("solar"));
        mvc.perform(get("/api/v1/CHL/regions").param("level", "prov"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(16)));
        mvc.perform(get("/api/v1/GRC/regions").param("level", "prov"))
                .andExpect(jsonPath("$.items", hasSize(14)))
                // 상위 단위 ID는 경계 파일 원래 이름 그대로, 화면 이름은 ADM1 파일의 name_en·name_ko
                .andExpect(jsonPath("$.items[?(@.id == 'Attikis')].name_en").value(hasItem("Attica")))
                .andExpect(jsonPath("$.items[?(@.id == 'Attikis')].label_ko").value(hasItem("아티키")));
        mvc.perform(get("/api/v1/CHL/regions").param("level", "prov"))
                .andExpect(jsonPath("$.items[?(@.id == 'Región del Bío-Bío')].name_en").value(hasItem("Biobío")))
                .andExpect(jsonPath("$.items[?(@.id == 'Región del Bío-Bío')].label_ko").value(hasItem("비오비오")));
        mvc.perform(get("/api/v1/KOR/regions").param("level", "prov"))   // 한국은 그대로: ID·label_ko 경북, name_en North Gyeongsang
                .andExpect(jsonPath("$.items[?(@.id == '경북')].name_en").value(hasItem("North Gyeongsang")));
    }

    @Test
    void region_detail_matches_spec_7_2() throws Exception {
        mvc.perform(get("/api/v1/KOR/regions/" + UISEONG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label_ko").value("의성군"))
                .andExpect(jsonPath("$.name_en").value("Uiseong-gun"))
                .andExpect(jsonPath("$.level").value("dist"))
                .andExpect(jsonPath("$.province").value("경북"))
                .andExpect(jsonPath("$.region_type").value("sparse"))
                .andExpect(jsonPath("$.judge_unit").value("province"))
                .andExpect(jsonPath("$.ref_province").value("경북"))
                .andExpect(jsonPath("$.basis").value("viirs_2012"))
                .andExpect(jsonPath("$.risk_from").value("province"))
                .andExpect(jsonPath("$.risk_share").value(0.53))   // 1장: 비율은 두 자리 (원값 0.531)
                .andExpect(jsonPath("$.neighbors[*].label_ko", hasItem("군위군")))
                .andExpect(jsonPath("$.neighbors[?(@.label_ko == '군위군')].province", hasItem("대구")));
    }

    @Test
    void region_lists() throws Exception {
        mvc.perform(get("/api/v1/KOR/regions?level=prov")).andExpect(jsonPath("$.items", hasSize(17)));
        mvc.perform(get("/api/v1/KOR/regions?level=dist")).andExpect(jsonPath("$.items", hasSize(229)));
        mvc.perform(get("/api/v1/CAL/regions?level=dist")).andExpect(jsonPath("$.items", hasSize(58)));
        mvc.perform(get("/api/v1/CAL/regions?level=prov")).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/v1/KOR/regions?q=의성"))
                .andExpect(jsonPath("$.items[0].id").value(UISEONG));
        mvc.perform(get("/api/v1/KOR/regions?level=nat"))
                .andExpect(jsonPath("$.items[0].label_ko").value("대한민국"));
    }

    @Test
    void bc_and_cal_districts() throws Exception {
        mvc.perform(get("/api/v1/BC/regions/BC-RD-60"))
                .andExpect(jsonPath("$.label_ko").value("Peace River"))
                .andExpect(jsonPath("$.province").doesNotExist());
        mvc.perform(get("/api/v1/CAL/regions/52423323B88547646561946"))
                .andExpect(jsonPath("$.label_ko").value("Butte"))
                .andExpect(jsonPath("$.region_type").value("sufficient"))
                .andExpect(jsonPath("$.judge_unit").value("self"));
    }

    @Test
    void geo() throws Exception {
        mvc.perform(get("/api/v1/KOR/geo?level=prov")).andExpect(jsonPath("$.features", hasSize(17)));
        mvc.perform(get("/api/v1/KOR/geo?parent=경북"))
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.features[*].properties.label_ko", hasItem("의성군")));
        mvc.perform(get("/api/v1/CAL/geo?level=nat")).andExpect(jsonPath("$.features", hasSize(1)));
    }

    @Test
    void errors() throws Exception {
        mvc.perform(get("/api/v1/KOR/regions/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        mvc.perform(get("/api/v1/JPN/meta"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COUNTRY_NOT_READY"));
    }
}
