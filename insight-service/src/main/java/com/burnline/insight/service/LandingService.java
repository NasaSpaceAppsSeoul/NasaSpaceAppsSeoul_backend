package com.burnline.insight.service;

import com.burnline.common.client.InternalClient;
import com.burnline.common.domain.Country;
import com.burnline.common.domain.Rules;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 명세 7-10 랜딩: 숫자 카드 3개, 보정 전·후 연간 화재 칸, 공식 기록 대비 단절 검정, 위성끼리 단절 검정, 크기별 감지율.
 * 보정 전 값·공식 피해면적·검정 원자료는 spatial, 보정 후 값·대표 달은 timeseries.
 */
@Service
public class LandingService {

    /**
     * 숫자 카드 ②의 대표 달 (나라별 가장 큰 산불 달).
     * 그리스는 위성 값이 가장 큰 2001-10이 농업 소각이라 산불이 가장 컸던 2007-08을 쓴다 (방법론 페이지와 같음).
     */
    static final Map<Country, String> CASE_MONTH = Map.of(Country.KOR, "2025-03", Country.CAL, "2020-09", Country.BC, "2023-05",
            Country.GRC, "2007-08", Country.CHL, "2023-02", Country.NSW, "2019-12");

    private final InternalClient timeseries;
    private final InternalClient spatial;

    public LandingService(@Qualifier("timeseriesClient") InternalClient timeseries,
                          @Qualifier("spatialClient") InternalClient spatial) {
        this.timeseries = timeseries;
        this.spatial = spatial;
    }

    /** 응답 본문 + 캐시용 최근 달 여부 (나라 시계열에 partial·nrt 달이 들어 있으면 true) */
    public record Result(Map<String, Object> body, boolean recent) {
    }

    public Result landing(Country c) {
        JsonNode raw = spatial.get("/internal/landing/{c}", c.name());
        JsonNode nat = timeseries.get("/api/v1/{c}/series/{id}", Map.of("from", "2001-01", "to", "2025-12"), c.name(), c.name());

        // 보정 후 연간 합계, 대표 달
        Map<Integer, Double> after = new TreeMap<>();
        JsonNode caseMonth = null;
        for (JsonNode s : nat.path("months")) {
            String mo = s.path("month").asText();
            if (s.get("value").isNumber()) {
                after.merge(Integer.parseInt(mo.substring(0, 4)), s.get("value").asDouble(), Double::sum);
            }
            if (mo.equals(CASE_MONTH.get(c))) {
                caseMonth = s;
            }
        }
        List<Double> afterList = new ArrayList<>();
        raw.path("years").forEach(y -> afterList.add(Math.round(after.getOrDefault(y.asInt(), 0.0) * 10) / 10.0));

        // ① 공식 피해면적 (한국은 KOSIS 2025, CAL·BC는 공식 기록 연 합계 최대 해)
        Map<String, Object> area = new LinkedHashMap<>();
        area.put("key", "burned_area_record");
        if (c == Country.KOR) {
            area.put("year", 2025);
            area.put("value", 105099);
            area.put("source", "KOSIS");
        } else {
            String best = null;
            long bestHa = -1;
            for (var e : raw.path("official_area_by_year").properties()) {
                if (e.getValue().asLong() > bestHa) {
                    bestHa = e.getValue().asLong();
                    best = e.getKey();
                }
            }
            area.put("year", best == null ? null : Integer.parseInt(best));
            area.put("value", bestHa < 0 ? null : bestHa);
            area.put("source", c.labels().get("official"));
        }
        area.put("unit", "ha");

        // ② 대표 달
        Map<String, Object> cs = new LinkedHashMap<>();
        cs.put("key", "case_month_ratio");
        cs.put("month", CASE_MONTH.get(c));
        cs.put("value", caseMonth == null ? null : num(caseMonth.get("ratio_to_median")));
        cs.put("cells", caseMonth == null ? null : num(caseMonth.get("value")));
        cs.put("rank", caseMonth == null ? null : intOr(caseMonth.get("rank")));
        cs.put("n", caseMonth == null ? null : intOr(caseMonth.get("n")));

        // ③ 위험 시기 비중
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("key", "risk_share");
        Double share = num(raw.path("national_risk").get("share"));
        risk.put("value", share == null ? null : Math.round(share * 100) / 100.0);   // 명세 1장: 비율은 두 자리
        risk.put("months", raw.path("national_risk").get("months"));

        // 공식 기록 대비 단절 (한국 KOSIS 건수, CAL·BC 공식 기록 연 건수)
        Map<String, Object> official = new LinkedHashMap<>();
        JsonNode k = raw.path("kosis_annual");
        JsonNode rawBreak = k.has("원값 이어 붙이기") ? k.get("원값 이어 붙이기") : raw.path("annual_records").get("원값 이어 붙이기 / 공식 건수");
        JsonNode calBreak = k.has("화재 칸 (보정)") ? k.get("화재 칸 (보정)") : raw.path("annual_records").get("화재 칸 (보정) / 공식 건수");
        Double before = rawBreak == null ? null : num(rawBreak.get("after_over_before"));
        Double afterRatio = calBreak == null ? null : num(calBreak.get("after_over_before"));
        official.put("source", c == Country.KOR ? "산림청 (KOSIS)" : c.labels().get("official"));
        official.put("before", before);
        official.put("after", afterRatio);
        official.put("inconclusive", Rules.inconclusive(afterRatio));   // A /meta와 같은 기준 (0.75~1.33 밖)

        // 위성끼리 단절 검정
        JsonNode bt = raw.path("break_test");
        Map<String, Object> breakTest = new LinkedHashMap<>();
        breakTest.put("before", ratio(bt.path("이어 붙이기")));
        breakTest.put("after", ratio(bt.path("H 하모니제이션")));
        JsonNode j = bt.path("이어 붙이기");
        breakTest.put("jump_raw", j.path("jump").isNumber() ? j.path("jump").asDouble() : null);

        // 크기별 감지율
        List<Map<String, Object>> detect = new ArrayList<>();
        int detectN = 0;
        // jsonb는 키를 길이순으로 다시 정렬한다 → 원천 구간 순서(작은 산불 → 큰 산불)로 되돌린다
        List<Map.Entry<String, JsonNode>> bins = new ArrayList<>(raw.path("detect_by_size").properties());
        bins.sort(Comparator.comparingInt(e -> binOrder(e.getKey())));
        for (var e : bins) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("bin", e.getKey());
            d.put("n", e.getValue().path("n").asInt());
            d.put("detected", num(e.getValue().get("detected")));
            d.put("chance", num(e.getValue().get("chance")));
            detectN += e.getValue().path("n").asInt();
            detect.add(d);
        }

        Map<String, Object> annual = new LinkedHashMap<>();
        annual.put("years", raw.get("years"));
        annual.put("before", raw.get("before"));
        annual.put("after", afterList);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", c.name());
        out.put("data_version", raw.path("data_version").asText(null));
        out.put("labels", c.labels());
        out.put("stats", List.of(area, cs, risk));
        out.put("annual", annual);
        out.put("official_break", official);
        out.put("break_test", breakTest);
        out.put("detect_by_size", detect);
        out.put("detect_n", detectN);
        boolean recent = nat.path("months").findValuesAsText("status").stream().anyMatch(st -> !"final".equals(st));
        return new Result(out, recent);
    }

    static final List<String> BIN_ORDER = List.of("0.1 ha 미만", "0.1~1", "1~10", "10~100", "100 ha 이상");

    static int binOrder(String bin) {
        int i = BIN_ORDER.indexOf(bin);
        return i < 0 ? BIN_ORDER.size() : i;
    }

    private static Map<String, Object> ratio(JsonNode b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ratio", num(b.get("ratio_after_2012")));
        m.put("ci95", b.get("ratio_ci95"));
        return m;
    }

    private static Double num(JsonNode n) {
        return n == null || !n.isNumber() ? null : n.asDouble();
    }

    private static Integer intOr(JsonNode n) {
        return n == null || !n.isNumber() ? null : n.asInt();
    }
}
