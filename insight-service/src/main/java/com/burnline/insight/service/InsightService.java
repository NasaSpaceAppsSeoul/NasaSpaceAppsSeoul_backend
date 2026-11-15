package com.burnline.insight.service;

import com.burnline.common.client.InternalClient;
import com.burnline.common.client.UpstreamException;
import com.burnline.common.domain.Country;
import com.burnline.common.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 명세 4장 인사이트 카드 1~9 조합 (명세 7-8 응답 모양).
 * 판정은 timeseries, 사실값은 spatial, 구역·이웃은 catalog에서 받는다. 이 서비스는 계산을 거의 하지 않는다.
 * 하위 서비스가 실패하면 그 카드만 null로 두고 unavailable에 적는다 (나머지 카드는 그대로 보낸다).
 */
@Slf4j
@Service
public class InsightService {

    private final InternalClient catalog;
    private final InternalClient timeseries;
    private final InternalClient spatial;
    private final ObjectMapper mapper;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public InsightService(@Qualifier("catalogClient") InternalClient catalog,
                          @Qualifier("timeseriesClient") InternalClient timeseries,
                          @Qualifier("spatialClient") InternalClient spatial, ObjectMapper mapper) {
        this.catalog = catalog;
        this.timeseries = timeseries;
        this.spatial = spatial;
        this.mapper = mapper;
    }

    /** 응답 본문 + 캐시용 최근 달 여부 (판정 status가 final이 아니거나 카드 일부를 못 만들었으면 true) */
    public record Result(Map<String, Object> body, boolean recent) {
    }

    public Result insight(Country c, String id, String month) {
        List<String> unavailable = new ArrayList<>();
        int m = Integer.parseInt(month.substring(5));

        // 1) 구역(없으면 404 그대로), 판정, 달 상세, 시계열은 동시에
        JsonNode region = catalog.get("/internal/regions/{c}/{id}", c.name(), id);
        if (!"dist".equals(region.path("level").asText())) {
            // 명세 3장 #10: /insight/{dist_id} — 시·도·나라는 대시보드(/judgement, /series)로
            throw ApiException.invalidParam("insight is for districts (level dist); '%s' is %s".formatted(id, region.path("level").asText()));
        }
        var judgeF = async(() -> judge(c, id, month));
        var detailF = async(() -> spatial.get("/internal/month-detail/{c}/{id}", Map.of("month", month), c.name(), id));
        var ownF = async(() -> series(c, id, "2012-02", "2025-12"));
        String refId = region.path("ref_province").asText(null);
        var refF = refId == null ? null : async(() -> series(c, refId,
                "harmonized_25y".equals(judgeOr(judgeF).path("ref").path("basis").asText()) ? "2001-01" : "2012-02", "2025-12"));
        // 명세 4장: 자기 시·도 판정 prov{} (빌려 쓴 ref와 같은 시·도면 ref를 그대로 쓴다)
        String provId = region.path("province").asText(null);
        var provF = provId == null || provId.equals(refId) ? null : async(() -> provCard(c, provId, month));
        List<CompletableFuture<Map<String, Object>>> nbF = new ArrayList<>();
        for (JsonNode n : region.path("neighbors")) {
            nbF.add(async(() -> neighbor(c, n, month, provId)));
        }

        JsonNode judge = take(judgeF, "judge", unavailable);
        JsonNode detail = take(detailF, "spatial", unavailable);
        JsonNode own = take(ownF, "series", unavailable);
        JsonNode ref = refF == null ? null : take(refF, "series_ref", unavailable);
        Object prov = provId == null ? null
                : provF == null ? (judge == null ? null : judge.get("ref")) : take(provF, "prov", unavailable);

        // 카드 1 머리
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("value", judge == null ? null : num(judge.get("value")));
        head.put("chip", judge == null ? null : text(judge.get("chip")));
        head.put("region_type", region.path("region_type").asText(null));
        head.put("judge_unit", region.path("judge_unit").asText(null));
        // 드문 지역은 /judgement의 rank가 null(명세 7-5) → 자기 시계열의 그달 순위를 쓴다 (명세 7-8: rank 1, n 14)
        JsonNode ownMonth = own == null ? null : seriesMonth(own, month);
        Integer jRank = judge == null ? null : intOr(judge.get("rank"));
        Integer jN = judge == null ? null : intOr(judge.get("n"));
        head.put("rank", jRank != null || ownMonth == null ? jRank : intOr(ownMonth.get("rank")));
        head.put("n", jN != null || ownMonth == null ? jN : intOr(ownMonth.get("n")));
        head.put("reason", judge == null ? null : text(judge.get("reason")));
        // 2012-02 전 시·군·구 달은 값이 없다(PRE_2012_DISTRICT) → 감지 일수·하루 최대도 0이 아니라 null
        boolean pre2012 = judge != null && "PRE_2012_DISTRICT".equals(judge.path("reason").asText());
        head.put("fire_days", detail == null || pre2012 ? null : intOr(detail.get("fire_days")));
        head.put("dmax", detail == null || pre2012 ? null : intOr(detail.get("dmax")));
        head.put("dmax_date", detail == null || pre2012 ? null : text(detail.get("dmax_date")));
        head.put("annual", detail == null ? null : num(detail.path("facts").get("annual")));
        head.put("annual_all", detail == null ? null : num(detail.path("facts").get("annual_all")));

        // 카드 2 달력
        Map<String, Object> calendar = null;
        if (detail != null) {
            calendar = new LinkedHashMap<>();
            calendar.put("daily", detail.get("daily"));
            calendar.put("main_cluster", detail.get("main_cluster"));
            calendar.put("clusters", detail.get("clusters"));
            calendar.put("near50", detail.get("near50"));
        }

        // 카드 3 연도별 · 카드 4 순위
        List<Map<String, Object>> strip = own == null ? null : strip(own, m);
        Map<String, Object> years = new LinkedHashMap<>();
        years.put("strip", strip);
        boolean self = "self".equals(region.path("judge_unit").asText());
        years.put("median", judge == null ? null : num(judge.get("median")));
        years.put("band_low", judge == null || !self ? null : num(judge.get("band_low")));
        years.put("band_high", judge == null || !self ? null : num(judge.get("band_high")));
        years.put("yrs_fire", detail == null ? null : intOr(detail.path("facts").get("yrs_fire")));
        years.put("longterm", detail == null ? null : longterm(detail.path("facts").get("longterm")));
        Map<String, Object> rank = new LinkedHashMap<>();
        rank.put("top_years", strip == null ? null : topYears(strip, Integer.parseInt(month.substring(0, 4))));

        // 카드 6 계절
        Map<String, Object> season = new LinkedHashMap<>();
        season.put("own_mt", own == null ? null : monthly(own, "2012-02"));
        season.put("ref_mt", ref == null ? null : monthly(ref, null));
        season.put("ref_id", refId);
        season.put("risk_months", region.get("risk_months"));
        season.put("risk_share", region.get("risk_share"));
        season.put("risk_from", region.path("risk_from").asText(null));

        // 카드 7 지도
        Map<String, Object> map = null;
        if (detail != null) {
            map = new LinkedHashMap<>();
            map.put("center", region.get("center"));   // /regions와 같은 중심점 (좌표 소수 3자리). 50 km 계산은 spatial이 따로 한다
            map.put("points", detail.get("points"));
            map.put("rec", detail.get("rec"));
        }

        // 카드 8 주변 지역
        List<Map<String, Object>> neighbors = new ArrayList<>();
        for (var f : nbF) {
            Map<String, Object> n = take(f, null, null);
            if (n != null) {
                neighbors.add(n);
            }
        }
        if (neighbors.size() < nbF.size()) {
            unavailable.add("neighbors");
        }
        neighbors.sort(Comparator.comparingDouble(n -> n.get("v") == null ? -1 : -((Number) n.get("v")).doubleValue()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("country", c.name());
        out.put("month", month);
        out.put("label_ko", region.path("label_ko").asText(null));
        out.put("province", region.path("province").asText(null));
        out.put("data_version", detail == null ? null : text(detail.get("data_version")));
        out.put("head", head);
        out.put("prov", prov);
        out.put("ref", judge == null ? null : judge.get("ref"));
        out.put("calendar", calendar);
        out.put("years", years);
        out.put("rank", rank);
        out.put("season", season);
        out.put("official", detail == null ? null : detail.get("official"));
        out.put("detect_rate", detail == null ? null : detail.get("detect_rate"));   // 카드 5 감지율 한 줄
        out.put("map", map);
        out.put("neighbors", neighbors);
        out.put("unavailable", unavailable);
        boolean recent = !unavailable.isEmpty() || judge == null || !"final".equals(judge.path("status").asText());
        return new Result(out, recent);
    }

    // ---------------------------------------------------------------- 조각

    private JsonNode judge(Country c, String id, String month) {
        return timeseries.get("/internal/judge", Map.of("country", c.name(), "region_id", id, "month", month));
    }

    private JsonNode series(Country c, String id, String from, String to) {
        return timeseries.get("/api/v1/{c}/series/{id}", Map.of("from", from, "to", to), c.name(), id);
    }

    /** 시·도 판정 한 묶음 (ref{}와 같은 모양: 명세 7-5) */
    private Map<String, Object> provCard(Country c, String provId, String month) {
        JsonNode j = judge(c, provId, month);
        JsonNode r = catalog.get("/internal/regions/{c}/{id}", c.name(), provId);
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", provId);
        x.put("label_ko", r.path("label_ko").asText(null));
        x.put("value", num(j.get("value")));
        x.put("chip", text(j.get("chip")));
        x.put("rank", intOr(j.get("rank")));
        x.put("n", intOr(j.get("n")));
        x.put("ratio_to_median", num(j.get("ratio_to_median")));
        x.put("basis", r.path("basis").asText(null));
        return x;
    }

    private Map<String, Object> neighbor(Country c, JsonNode n, String month, String ownProvince) {
        String nid = n.path("id").asText();
        JsonNode j = judge(c, nid, month);
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", nid);
        x.put("label_ko", n.path("label_ko").asText(null));
        // 같은 시·도 이웃은 원천에 province가 없다 → 자기 시·도로 채운다 (다른 시·도 이웃만 원천 값)
        x.put("province", n.path("province").asText(ownProvince));
        x.put("region_type", text(j.get("region_type")));
        x.put("v", num(j.get("value")));
        x.put("rank", intOr(j.get("rank")));
        x.put("n", intOr(j.get("n")));
        return x;
    }

    /** 시계열에서 그달 한 줄 (없으면 null) */
    static JsonNode seriesMonth(JsonNode series, String month) {
        for (JsonNode s : series.path("months")) {
            if (month.equals(s.path("month").asText())) {
                return s;
            }
        }
        return null;
    }

    /** 원천 품질 표기(높음·보통·낮음) → 명세 1장 코드값 */
    static final Map<String, String> QUALITY = Map.of("높음", "high", "보통", "medium", "낮음", "low");

    /** 장기 사실값: quality만 코드값으로 바꾼 사본 */
    static JsonNode longterm(JsonNode lt) {
        if (lt == null || !lt.isObject()) {
            return lt;
        }
        com.fasterxml.jackson.databind.node.ObjectNode copy = ((com.fasterxml.jackson.databind.node.ObjectNode) lt).deepCopy();
        String q = lt.path("quality").asText(null);
        if (q != null && QUALITY.containsKey(q)) {
            copy.put("quality", QUALITY.get(q));
        }
        return copy;
    }

    /** 2012~2025 같은 달 값 (2012-01은 VIIRS 전이라 뺌) */
    static List<Map<String, Object>> strip(JsonNode series, int m) {
        List<Map<String, Object>> out = new ArrayList<>();
        String mm = "-%02d".formatted(m);
        for (JsonNode s : series.path("months")) {
            String mo = s.path("month").asText();
            int y = Integer.parseInt(mo.substring(0, 4));
            if (mo.endsWith(mm) && y >= 2012 && y <= 2025 && !"2012-01".equals(mo) && s.get("value").isNumber()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("year", y);
                x.put("v", s.get("value").asDouble());
                out.add(x);
            }
        }
        return out;
    }

    /** 상위 5개 해 + 올해 (공동 순위 표시) */
    static List<Map<String, Object>> topYears(List<Map<String, Object>> strip, int year) {
        List<Map<String, Object>> sorted = new ArrayList<>(strip);
        sorted.sort(Comparator.comparingDouble((Map<String, Object> d) -> -(Double) d.get("v"))
                .thenComparingInt(d -> -(Integer) d.get("year")));
        List<Map<String, Object>> top = new ArrayList<>(sorted.subList(0, Math.min(5, sorted.size())));
        if (top.stream().noneMatch(d -> (Integer) d.get("year") == year)) {
            sorted.stream().filter(d -> (Integer) d.get("year") == year).findFirst().ifPresent(top::add);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : top) {
            double v = (Double) d.get("v");
            Map<String, Object> x = new LinkedHashMap<>(d);
            x.put("rank", strip.stream().filter(s -> (Double) s.get("v") > v).count() + 1);
            x.put("tied", strip.stream().filter(s -> (Double) s.get("v") == v).count() > 1);
            out.add(x);
        }
        return out;
    }

    /** 월별 합계 12개 (계절 카드 비중의 원자료) */
    static List<Double> monthly(JsonNode series, String from) {
        double[] sum = new double[12];
        for (JsonNode s : series.path("months")) {
            String mo = s.path("month").asText();
            if ((from != null && mo.compareTo(from) < 0) || !s.get("value").isNumber()) {
                continue;
            }
            sum[Integer.parseInt(mo.substring(5)) - 1] += s.get("value").asDouble();
        }
        List<Double> out = new ArrayList<>();
        for (double v : sum) {
            out.add(Math.round(v * 10) / 10.0);
        }
        return out;
    }

    // ---------------------------------------------------------------- 도움

    private <T> CompletableFuture<T> async(Supplier<T> s) {
        return CompletableFuture.supplyAsync(s, pool);
    }

    private JsonNode judgeOr(CompletableFuture<JsonNode> f) {
        try {
            return f.join();
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    /** 실패하면 null + unavailable에 이름. 404(구역 없음 등)는 그대로 던진다. */
    private <T> T take(CompletableFuture<T> f, String name, List<String> unavailable) {
        try {
            return f.join();
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof UpstreamException u && u.status() != null && u.status() < 500 && "judge".equals(name)) {
                throw u;   // 달 범위·형식 오류는 사용자에게 그대로
            }
            log.warn("upstream failed ({}): {}", name, cause.toString());
            if (unavailable != null && name != null) {
                unavailable.add(name);
            }
            return null;
        }
    }

    private static Double num(JsonNode n) {
        return n == null || !n.isNumber() ? null : n.asDouble();
    }

    private static Integer intOr(JsonNode n) {
        return n == null || !n.isNumber() ? null : n.asInt();
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }
}
