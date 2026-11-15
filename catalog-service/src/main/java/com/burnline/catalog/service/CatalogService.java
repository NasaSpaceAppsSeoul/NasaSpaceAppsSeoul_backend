package com.burnline.catalog.service;

import com.burnline.catalog.repo.CatalogRepository;
import com.burnline.catalog.repo.RegionRow;
import com.burnline.common.domain.Country;
import com.burnline.common.domain.Rules;
import com.burnline.common.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** catalog 응답 만들기 (명세 3장 #1~#4, 7-1, 7-2, 7-13) */
@Service
@RequiredArgsConstructor
public class CatalogService {

    /** 보통 해 범위 = 같은 달 15~85 퍼센타일 + 2√평년 (파이프라인 BAND_Q, BAND_NOISE) */
    private static final List<Double> BAND_Q = List.of(0.15, 0.85);
    private static final int BAND_NOISE = 2;

    private final CatalogRepository repo;

    /** 명세 7-1, 7-13 */
    public Map<String, Object> meta(Country country) {
        JsonNode doc = repo.meta(country).orElseThrow(() -> ApiException.countryNotReady(country.name()));
        JsonNode range = doc.path("data_range");
        JsonNode regions = doc.path("regions");
        JsonNode harmonized = doc.path("break_test").path("H 하모니제이션");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("country", country.name());
        m.put("data_version", doc.path("generated").asText(null));
        m.put("generated_at", doc.path("generated_at").asText(doc.path("generated").asText(null)));   // 7-1: 시각 포함
        // 마지막 관측일 = NOAA-20 끝 (명세 예시: KOR 2026-10-31, CAL 2026-11-09)
        m.put("data_end", range.path("noaa20").path(1).asText(null));
        m.put("unit", "NOAA-20 기준 화재 칸 (0.01° 칸 × 현지 하루)");
        m.put("data_range", Map.of(
                "modis", range.path("modis"), "viirs_snpp", range.path("viirs_snpp"),
                "noaa20", range.path("noaa20"), "noaa21", range.path("noaa21")));
        m.put("judgement_params", Map.of(
                "band_q", BAND_Q, "band_noise", BAND_NOISE,
                "min_annual", regions.path("sufficient_min_viirs_annual").asDouble(),
                "risk_share", regions.path("risk_share").asDouble()));
        m.put("calibration", Map.of(
                "chain", "Terra → MODIS → S-NPP → NOAA-20",
                "modis_to_snpp_by_month", doc.path("calibration").path("k_by_month")));
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("break_test_ratio", harmonized.path("ratio_after_2012").isNumber()
                ? harmonized.get("ratio_after_2012").asDouble() : null);
        validation.put("break_test_ci95", harmonized.path("ratio_ci95"));
        validation.put("official_break", officialBreak(doc.path("external_validation")));
        m.put("validation", validation);
        m.put("filters", doc.path("filters"));   // 3장 #1 필터 단계별 건수 (원본 그대로)
        m.put("labels", country.labels());
        m.put("sources", List.of("NASA FIRMS", country.boundaryLicense(), (String) country.labels().get("official")));
        // 명세 6장 다국가: 시간대, 경계 출처(labels.boundary), 외부 검증 자료 유무
        m.put("time_basis", country.timeBasis());
        m.put("external_validation", externalValidation(doc.path("external_validation")));
        m.put("source_products", doc.path("sources"));
        m.put("method_stats", methodStats(doc));
        return m;
    }

    /**
     * 공식 기록(산림청 KOSIS·CAL FIRE·BC Wildfire Service) 대비 2012 단절 검정 (명세 7-10 kosis_check, 7-13 official_break).
     * before = 보정 전(원값 이어 붙이기), after = 보정 후의 "2012 뒤 / 앞" 비율 (공식 건수 기준).
     * inconclusive: 보정 후 비율이 0.75~1.33 밖이면 true (Rules.inconclusive, B /landing과 같은 기준). 예: BC 2.14 → true.
     */
    private static Map<String, Object> officialBreak(JsonNode ev) {
        JsonNode test = (ev.path("kosis_annual").isMissingNode() ? ev.path("annual_records") : ev.path("kosis_annual"))
                .path("independent_break_test");
        JsonNode before = null;
        JsonNode after = null;
        for (var it = test.properties().iterator(); it.hasNext(); ) {
            var e = it.next();
            boolean count = !e.getKey().contains("/") || e.getKey().endsWith("공식 건수");
            if (!count) {
                continue;
            }
            if (e.getKey().startsWith("원값")) {
                before = e.getValue();
            } else if (e.getKey().startsWith("화재 칸")) {
                after = e.getValue();
            }
        }
        if (before == null || after == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", before.path("after_over_before").asDouble());
        m.put("after", after.path("after_over_before").asDouble());
        m.put("inconclusive", Rules.inconclusive(after.path("after_over_before").asDouble()));
        return m;
    }

    /** 외부 검증 자료(산림청·CAL FIRE·BC Wildfire Service) 유무와 출처·건수·기간. 없으면 available false */
    private static Map<String, Object> externalValidation(JsonNode ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean available = ev.path("records").asInt(0) > 0;
        m.put("available", available);
        m.put("source", available ? ev.path("source").asText(null) : null);
        m.put("records", available ? ev.path("records").asInt() : null);
        m.put("period", available ? ev.path("period") : null);
        return m;
    }

    /**
     * 방법론 페이지(/method) 숫자 카드 원천 — meta.json에서 직접 읽어 그대로 노출.
     * grid_sensitivity: 격자 해상도별 월별 R²·연간 오차 (칸 크기 표 03).
     * loocv_annual_error_pct: H 하모니제이션 적용 후 MODIS→S-NPP 연간 오차 (validation.B, 검증 표 05).
     * calibration_error_pct: 계수별 연간 오차 — terra_to_modis / snpp_to_noaa20 / noaa21_to_noaa20 (계수 표 04).
     * rank_corr: 연간 순위 상관 — 건수·피해면적·100ha이상 (검증 표 05).
     */
    private static Map<String, Object> methodStats(JsonNode doc) {
        Map<String, Object> m = new LinkedHashMap<>();

        m.put("grid_sensitivity", doc.path("grid_sensitivity"));

        JsonNode valB = doc.path("validation").path("B");
        m.put("loocv_annual_error_pct", valB.path("annual_mape_pct").isNumber()
                ? valB.path("annual_mape_pct").asDouble() : null);

        Map<String, Object> calErr = new LinkedHashMap<>();
        for (String key : List.of("terra_to_modis", "snpp_to_noaa20", "noaa21_to_noaa20")) {
            JsonNode v = doc.path("calibration").path(key).path("validation").path("annual_mape_pct");
            calErr.put(key, v.isNumber() ? v.asDouble() : null);
        }
        m.put("calibration_error_pct", calErr);

        JsonNode rc = doc.path("external_validation").path("rank_correlation");
        JsonNode rho = doc.path("external_validation").path("big_fires_area_vs_cells_rho");
        Map<String, Object> rankCorr = new LinkedHashMap<>();
        rankCorr.put("annual_vs_count", rc.path("annual_vs_count").isNumber() ? rc.path("annual_vs_count").asDouble() : null);
        rankCorr.put("annual_vs_area", rc.path("annual_vs_area").isNumber() ? rc.path("annual_vs_area").asDouble() : null);
        rankCorr.put("big_fires_vs_cells", rho.isNumber() ? rho.asDouble() : null);
        m.put("rank_corr", rankCorr);

        return m;
    }

    /** 명세 1장: 비율은 두 자리 */
    private static Double round2(Double v) {
        return v == null ? null : Math.round(v * 100) / 100.0;
    }

    /** 명세 3장 #2 */
    public Map<String, Object> regions(Country country, String level, String q) {
        List<Map<String, Object>> items = repo.regions(country, level, q, null).stream().map(this::summary).toList();
        return Map.of("items", items);
    }

    /** 명세 7-2 */
    public Map<String, Object> region(Country country, String id) {
        RegionRow r = repo.region(country, id).orElseThrow(() -> ApiException.regionNotFound(country.name(), id));
        Map<String, Object> m = summary(r);
        List<String> ids = new ArrayList<>();
        r.neighbors().forEach(n -> ids.add(n.asText()));
        List<Map<String, Object>> neighbors = repo.regionsByIds(country, ids).stream().map(n -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", n.id());
            x.put("label_ko", n.labelKo());
            if (n.province() != null && !n.province().equals(r.province())) {
                x.put("province", n.province());   // 다른 시·도일 때만 (명세 예시: 군위군 → 대구)
            }
            return x;
        }).toList();
        m.put("neighbors", neighbors);
        m.put("judge_unit", r.judgeUnit());
        m.put("ref_province", r.refProvince());
        m.put("basis", r.basis());
        m.put("risk_months", r.riskMonths());
        m.put("risk_share", round2(r.riskShare()));
        m.put("risk_from", r.riskFrom());
        m.put("peak_month", r.peakMonth());   // 위험 시기의 정점 달. 상위 단위(시·도 등)는 아직 null
        return m;
    }

    /**
     * 명세 3장 #4: 단순화 GeoJSON (속성 id, label_ko, province).
     * level 기본 dist. KOR prov는 geoBoundaries ADM1, CAL·BC는 nat(주 경계)도 있다.
     */
    public Map<String, Object> geo(Country country, String level, String parent) {
        String lv = level == null ? "dist" : level;
        List<Map<String, Object>> features = repo.geometries(country, lv, parent).stream().map(r -> {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("id", r.id());
            props.put("label_ko", r.labelKo());
            props.put("province", r.province());
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "Feature");
            f.put("properties", props);
            f.put("geometry", r.geometry());
            return f;
        }).toList();
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", features);
        return fc;
    }

    private Map<String, Object> summary(RegionRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("label_ko", r.labelKo());
        m.put("name_en", r.nameEn());
        m.put("level", r.level());
        m.put("province", r.province());
        m.put("region_type", r.regionType());
        m.put("center", r.center());
        m.put("bbox", r.bbox());
        return m;
    }
}
