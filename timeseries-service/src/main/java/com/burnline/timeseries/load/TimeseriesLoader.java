package com.burnline.timeseries.load;

import com.burnline.common.domain.Codes.Basis;
import com.burnline.common.domain.Codes.JudgeUnit;
import com.burnline.common.domain.Codes.Level;
import com.burnline.common.domain.Codes.Quality;
import com.burnline.common.domain.Codes.RegionType;
import com.burnline.common.domain.Country;
import com.burnline.common.domain.Rules;
import com.burnline.common.load.Geo;
import com.burnline.common.load.Loader;
import com.burnline.common.load.SourceFiles;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 원천 → timeseries.regions / series / baseline. 원본 배열을 "구역 × 달 한 줄"로 펼치면서 명세 코드값으로 바꾼다.
 * <ul>
 *   <li>nat: calendar.json (달마다 한 항목) + climatology.json. 기간 2001~2025 (보정 25년)</li>
 *   <li>prov: calendar_by_province.json (한국만). basis에 따라 25년 또는 2012~</li>
 *   <li>dist: calendar_by_region.json의 viirs 블록 (2012-02~ 단일 센서). 25년 값('value')은 비교에 쓰지 않는다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeseriesLoader implements Loader {

    private static final int VIIRS_FIRST_YEAR = 2012;

    private final SourceFiles files;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    @Override
    public boolean loaded(Country country) {
        Integer n = jdbc.queryForObject("select count(*) from timeseries.data_version where country = ?",
                Integer.class, country.name());
        return n != null && n > 0;
    }

    @Override
    public void load(Country c) {
        JsonNode meta = files.output(c, "meta.json");
        JsonNode calendar = files.output(c, "calendar.json");
        JsonNode climatology = files.output(c, "climatology.json");
        JsonNode byRegion = files.output(c, "calendar_by_region.json");
        JsonNode geo = files.output(c, "regions.geojson");
        int periodEnd = byRegion.path("baseline_years").path(1).asInt(2025);
        int periodStart = byRegion.path("baseline_years").path(0).asInt(2001);
        String archiveEnd = files.noaa20ArchiveEnd(c).map(d -> d.toString().substring(0, 7)).orElse(null);
        log.info("timeseries {}: NOAA-20 SP ends {} (later months are status nrt)", c, archiveEnd);

        Batch b = new Batch();
        Map<String, JsonNode> natByMonth = new HashMap<>();

        // ---------------- 나라
        Map<Integer, Double> natMedian = new HashMap<>();
        for (JsonNode m : climatology) {
            natMedian.put(m.path("m").asInt(), num(m.get("median")));
        }
        List<Double> natValues = new ArrayList<>();
        for (JsonNode m : calendar) {
            String month = "%04d-%02d".formatted(m.path("year").asInt(), m.path("month").asInt());
            natByMonth.put(month, m);
            Double v = num(m.get("value"));
            natValues.add(v);
            Quality q = Rules.lowerIfLowBaseline(Rules.quality(m.path("quality").asText(null)),
                    natMedian.get(m.path("month").asInt()));
            b.series(c, c.nationalId(), month, v, num(m.get("raw_before")), m.path("source").asText(null), q,
                    Rules.estimated(Level.nat, month), status(m, month, archiveEnd), v == null ? null : intOrNull(m.get("rank_in_month")),
                    intOrNull(m.get("years_in_month")), num(m.get("ratio_to_median")));
        }
        for (JsonNode m : climatology) {
            b.baseline(c, c.nationalId(), m.path("m").asInt(), num(m.get("median")), num(m.get("q25")), num(m.get("q75")),
                    num(m.get("band_low")), num(m.get("band_high")));
        }
        b.region(c, c.nationalId(), Level.nat, c.labelKo(), RegionType.sufficient, JudgeUnit.SELF, null,
                Basis.harmonized_25y, periodStart, periodEnd, heatBins(natValues));

        // ---------------- 시·군·구 (원본 속성: 충분 여부, 대체 시·도, 이름)
        Map<String, JsonNode> props = new LinkedHashMap<>();
        Map<String, double[]> centers = new HashMap<>();
        for (JsonNode f : geo.path("features")) {
            JsonNode p = f.path("properties");
            props.put(p.path("id").asText(), p);
            centers.put(p.path("id").asText(), Geo.centroid(f.get("geometry")));
        }
        Map<String, String> koNames = c == Country.KOR ? SourceFiles.koreanDistrictNames() : Map.of();
        List<String> months = strings(byRegion.path("months"));
        byRegion.path("regions").properties().forEach(e -> {
            String id = e.getKey();
            JsonNode r = e.getValue();
            JsonNode v = r.path("viirs");
            JsonNode p = props.getOrDefault(id, r);
            RegionType type = Rules.regionType(p.path("sufficiency").asText(r.path("sufficiency").asText()));
            JsonNode fallback = p.path("fallback");
            JudgeUnit unit = type == RegionType.sufficient ? JudgeUnit.SELF
                    : Rules.judgeUnit(fallback.path("ref_from").asText(null));
            String ref = unit == JudgeUnit.SELF ? null
                    : c.single() ? c.nationalId() : fallback.path("ref_province").asText(null);
            String label = c == Country.KOR
                    ? koNames.getOrDefault(id, p.path("name").asText(id))
                    : p.path("name_ko").asText(p.path("name").asText(id));

            List<Double> values = new ArrayList<>();
            for (int i = 0; i < months.size(); i++) {
                String month = months.get(i);
                Double val = num(v.path("value").get(i));
                values.add(val);
                int mi = Integer.parseInt(month.substring(5)) - 1;
                Double med = num(v.path("median_by_month").get(mi));
                Double ratio = val == null || med == null || med <= 0 ? null : round2(val / med);
                JsonNode nat = natByMonth.get(month);
                // 원본 quality 배열은 25년 보정값('value')용이다. VIIRS 값은 센서 변환 단계가 나라와 같으므로
                // 같은 달 나라 품질에서 시작하고 (2018-04~ NOAA-20 직접 = high), 이 지역의 같은 달 평년이
                // 5칸 미만이면 한 단계 낮춘다 (명세 2장). 명세 7-5·7-8 예시(의성 2025-03 high)는 이 규칙과 다르다.
                Quality q = val == null || nat == null ? null
                        : Rules.lowerIfLowBaseline(Rules.quality(nat.path("quality").asText(null)), med);
                b.series(c, id, month, val, num(r.path("raw_before").get(i)),
                        val == null || nat == null ? null : nat.path("source").asText(null), q,
                        Rules.estimated(Level.dist, month), status(nat, month, archiveEnd), val == null ? null : intOrNull(v.path("rank").get(i)),
                        intOrNull(v.path("years_in_month").get(i)), ratio);
            }
            for (int m = 1; m <= 12; m++) {
                b.baseline(c, id, m, num(v.path("median_by_month").get(m - 1)), num(v.path("q25_by_month").get(m - 1)),
                        num(v.path("q75_by_month").get(m - 1)), num(v.path("band_low_by_month").get(m - 1)),
                        num(v.path("band_high_by_month").get(m - 1)));
            }
            b.region(c, id, Level.dist, label, type, unit, ref, Basis.viirs_2012, VIIRS_FIRST_YEAR, periodEnd, heatBins(values));
        });

        // ---------------- 시·도 (한국)
        if (!c.single()) {
            // 시·도 raw_before 계산용: 시·군·구 raw_before를 월별로 합산
            List<String> rMonths = strings(byRegion.path("months"));
            Map<String, Map<String, Double>> distRawByMonth = new HashMap<>();
            byRegion.path("regions").properties().forEach(e -> {
                Map<String, Double> m2v = new HashMap<>();
                JsonNode rb = e.getValue().path("raw_before");   // 시·군·구와 같은 최상위 raw_before (viirs 블록에는 없음)
                for (int i = 0; i < rMonths.size(); i++) {
                    Double rv = num(rb.get(i));
                    if (rv != null) m2v.put(rMonths.get(i), rv);
                }
                distRawByMonth.put(e.getKey(), m2v);
            });

            JsonNode byProv = files.output(c, "calendar_by_province.json");
            List<String> pMonths = strings(byProv.path("months"));
            Map<String, double[]> provCenter = new LinkedHashMap<>();
            Map<String, RegionType> provType = new LinkedHashMap<>();
            byProv.path("provinces").properties().forEach(e -> {
                double[] sum = new double[3];
                e.getValue().path("regions").forEach(id -> {
                    double[] ct = centers.get(id.asText());
                    if (ct != null) {
                        sum[0] += ct[0];
                        sum[1] += ct[1];
                        sum[2]++;
                    }
                });
                provCenter.put(e.getKey(), new double[]{sum[0] / sum[2], sum[1] / sum[2]});
                provType.put(e.getKey(), Rules.regionType(e.getValue().path("sufficiency").asText()));
            });
            Map<String, String> provKo = new LinkedHashMap<>();
            for (JsonNode f : geo.path("features")) {
                JsonNode p2 = f.path("properties");
                String pko = p2.path("province_ko").asText(null);
                String prov = p2.path("province").asText(null);
                if (pko != null && !pko.isEmpty() && prov != null) {
                    provKo.putIfAbsent(prov, pko);
                }
            }
            byProv.path("provinces").properties().forEach(e -> {
                String id = e.getKey();
                JsonNode p = e.getValue();
                Basis basis = Rules.basis(p.path("basis").asText());
                RegionType type = provType.get(id);
                JudgeUnit unit = type == RegionType.sufficient ? JudgeUnit.SELF : JudgeUnit.NEARBY_PROVINCE;
                String ref = unit == JudgeUnit.SELF ? null : provType.entrySet().stream()
                        .filter(x -> x.getValue() == RegionType.sufficient)
                        .min(Comparator.comparingDouble(x -> Geo.km(provCenter.get(x.getKey()), provCenter.get(id))))
                        .map(Map.Entry::getKey).orElse(c.nationalId());
                List<String> provDists = strings(p.path("regions"));
                List<Double> values = new ArrayList<>();
                for (int i = 0; i < pMonths.size(); i++) {
                    String month = pMonths.get(i);
                    Double val = num(p.path("value").get(i));
                    values.add(val);
                    JsonNode nat = natByMonth.get(month);
                    // 같은 달 나라 품질(센서 변환 단계가 같음)에서 시작해, 평년 5칸 미만이면 한 단계 낮춤 (명세 2장)
                    Double med = num(p.path("median_by_month").get(Integer.parseInt(month.substring(5)) - 1));
                    Quality q = val == null || nat == null ? null
                            : Rules.lowerIfLowBaseline(Rules.quality(nat.path("quality").asText(null)), med);
                    // raw_before: 소속 시·군·구 합산 (calendar_by_region 기준, 2012-02 전은 MODIS 원값). 소속 구역 값이 하나도 없으면 null
                    double rbSum = 0;
                    boolean rbAny = false;
                    for (String dist : provDists) {
                        Double drb = distRawByMonth.getOrDefault(dist, Map.of()).get(month);
                        if (drb != null) { rbSum += drb; rbAny = true; }
                    }
                    b.series(c, id, month, val, rbAny ? round1(rbSum) : null,
                            val == null || nat == null ? null : nat.path("source").asText(null), q,
                            Rules.estimated(Level.prov, month), status(nat, month, archiveEnd), val == null ? null : intOrNull(p.path("rank").get(i)),
                            intOrNull(p.path("years_in_month").get(i)), val == null ? null : num(p.path("ratio_to_median").get(i)));
                }
                for (int m = 1; m <= 12; m++) {
                    b.baseline(c, id, m, num(p.path("median_by_month").get(m - 1)), num(p.path("q25_by_month").get(m - 1)),
                            num(p.path("q75_by_month").get(m - 1)), num(p.path("band_low_by_month").get(m - 1)),
                            num(p.path("band_high_by_month").get(m - 1)));
                }
                b.region(c, id, Level.prov, provKo.getOrDefault(id, id), type, unit, ref, basis,
                        basis == Basis.harmonized_25y ? periodStart : VIIRS_FIRST_YEAR, periodEnd, heatBins(values));
            });
        }

        tx.executeWithoutResult(s -> {
            for (String t : List.of("series", "baseline", "regions")) {
                jdbc.update("delete from timeseries." + t + " where country = ?", c.name());
            }
            jdbc.batchUpdate("""
                    insert into timeseries.regions(country, id, level, label_ko, region_type, judge_unit, ref_id, basis,
                      period_start, period_end, heat_bins) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""", b.regions);
            jdbc.batchUpdate("""
                    insert into timeseries.series(country, region_id, month, value, raw_before, source, quality, estimated, status,
                      rank, n, ratio_to_median) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", b.series);
            jdbc.batchUpdate("""
                    insert into timeseries.baseline(country, region_id, m, median, q25, q75, band_low, band_high)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""", b.baseline);
            jdbc.update("""
                    insert into timeseries.data_version(country, version, loaded_at) values (?, ?, now())
                    on conflict (country) do update set version = excluded.version, loaded_at = now()""",
                    c.name(), meta.path("generated").asText());
        });
        log.info("timeseries {}: {} regions, {} series rows, {} baseline rows", c, b.regions.size(), b.series.size(),
                b.baseline.size());
    }

    /** 명세 2장 status (Rules.status). partial은 나라 달력의 partial 표시를 따른다 */
    private static String status(JsonNode natMonth, String month, String archiveEnd) {
        return Rules.status(natMonth != null && natMonth.path("partial").asBoolean(false), month, archiveEnd);
    }

    /** 명세 7-4 heat_bins: 0이 아닌 월값의 50·75·90 퍼센타일 (선형 보간, numpy 기본과 같음) */
    static List<Double> heatBins(List<Double> values) {
        double[] v = values.stream().filter(x -> x != null && x > 0).mapToDouble(Double::doubleValue).sorted().toArray();
        if (v.length == 0) {
            return List.of();
        }
        return Arrays.stream(new double[]{0.5, 0.75, 0.9}).mapToObj(q -> {
            double pos = q * (v.length - 1);
            int lo = (int) Math.floor(pos);
            int hi = Math.min(lo + 1, v.length - 1);
            return round1(v[lo] + (v[hi] - v[lo]) * (pos - lo));
        }).toList();
    }

    private static Double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }

    private static Integer intOrNull(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asInt();
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    /** 적재할 행 모음 */
    private static final class Batch {
        final List<Object[]> regions = new ArrayList<>();
        final List<Object[]> series = new ArrayList<>();
        final List<Object[]> baseline = new ArrayList<>();

        void region(Country c, String id, Level level, String label, RegionType type, JudgeUnit unit, String ref,
                    Basis basis, int start, int end, List<Double> heatBins) {
            regions.add(new Object[]{c.name(), id, level.name(), label, type.name(), unit.value(), ref, basis.name(),
                    start, end, heatBins.toString()});
        }

        void series(Country c, String id, String month, Double value, Double rawBefore, String source, Quality quality,
                    boolean estimated, String status, Integer rank, Integer n, Double ratio) {
            series.add(new Object[]{c.name(), id, month, value, rawBefore, source, quality == null ? null : quality.name(),
                    estimated, status, rank, n, ratio});
        }

        void baseline(Country c, String id, int m, Double median, Double q25, Double q75, Double low, Double high) {
            baseline.add(new Object[]{c.name(), id, m, median, q25, q75, low, high});
        }
    }
}
