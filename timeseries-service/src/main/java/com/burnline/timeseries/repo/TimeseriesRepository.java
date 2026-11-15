package com.burnline.timeseries.repo;

import com.burnline.common.domain.Country;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** timeseries 스키마 조회. 표 정의는 src/main/resources/db/migration. */
@Repository
@RequiredArgsConstructor
public class TimeseriesRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public record RegionInfo(String id, String level, String labelKo, String regionType, String judgeUnit,
                             String refId, String basis, int periodStart, int periodEnd, JsonNode heatBins) {
        public boolean selfJudged() {
            return "self".equals(judgeUnit) && "sufficient".equals(regionType);
        }
    }

    public record SeriesRow(String month, Double value, Double rawBefore, String source, String quality, boolean estimated,
                            String status, Integer rank, Integer n, Double ratioToMedian) {
    }

    /** 지도용 지역별 판정 일괄 조회 행 */
    public record MapJudgeRow(String regionId, String level, String regionType, String judgeUnit, String refId,
                              Double value, Integer rank, Double ratioToMedian, Double median,
                              Double bandLow, Double bandHigh) {
        public boolean selfJudged() {
            return "self".equals(judgeUnit) && "sufficient".equals(regionType);
        }
    }

    public record BaselineRow(int m, Double median, Double q25, Double q75, Double bandLow, Double bandHigh) {
    }

    public Optional<RegionInfo> region(Country country, String id) {
        return jdbc.query("""
                select id, level, label_ko, region_type, judge_unit, ref_id, basis, period_start, period_end,
                       heat_bins::text as heat_bins
                from timeseries.regions where country = ? and id = ?""",
                (rs, i) -> new RegionInfo(rs.getString("id"), rs.getString("level"), rs.getString("label_ko"),
                        rs.getString("region_type"), rs.getString("judge_unit"), rs.getString("ref_id"),
                        rs.getString("basis"), rs.getInt("period_start"), rs.getInt("period_end"),
                        tree(rs.getString("heat_bins"))),
                country.name(), id).stream().findFirst();
    }

    /** 나라 시계열의 첫 달·마지막 달 (월 범위 검증용) */
    public Optional<String[]> monthRange(Country country) {
        return jdbc.query("select min(month), max(month) from timeseries.series where country = ? and region_id = ?",
                (rs, i) -> rs.getString(1) == null ? null : new String[]{rs.getString(1), rs.getString(2)},
                country.name(), country.nationalId()).stream().filter(x -> x != null).findFirst();
    }

    public List<SeriesRow> series(Country country, String id, String from, String to) {
        return jdbc.query("""
                select * from timeseries.series
                where country = ? and region_id = ? and (?::text is null or month >= ?) and (?::text is null or month <= ?)
                order by month""", SERIES, country.name(), id, from, from, to, to);
    }

    public Optional<SeriesRow> seriesMonth(Country country, String id, String month) {
        return jdbc.query("select * from timeseries.series where country = ? and region_id = ? and month = ?",
                SERIES, country.name(), id, month).stream().findFirst();
    }

    /** 시즌 시작 달부터 그달까지 합 (명세 7-5 ytd_sum). 북반구는 1월, 남반구(CHL·NSW)는 7월부터 센다 */
    public Double ytd(Country country, String id, String month) {
        return jdbc.queryForObject("""
                select sum(value) from timeseries.series
                where country = ? and region_id = ? and month >= ? and month <= ?""",
                Double.class, country.name(), id, seasonStartMonth(country, month), month);
    }

    /** 시즌 시작 달부터 그달까지 평년 median 합 (ytd_ratio 분모) */
    public Double baselineYtd(Country country, String id, String month) {
        return jdbc.queryForObject("select sum(median) from timeseries.baseline where country = ? and region_id = ? and m in ("
                        + inList(seasonMonths(country, month)) + ")",
                Double.class, country.name(), id);
    }

    /** 그달이 속한 시즌의 첫 달 ("2026-02", CHL → "2025-07") */
    static String seasonStartMonth(Country country, String month) {
        int s = country.seasonStart(), y = Integer.parseInt(month.substring(0, 4)), mm = Integer.parseInt(month.substring(5));
        return "%04d-%02d".formatted(mm >= s ? y : y - 1, s);
    }

    /** 시즌 시작 달부터 그달까지의 달 번호 (CHL 2월 → 7,8,9,10,11,12,1,2) */
    static List<Integer> seasonMonths(Country country, String month) {
        int s = country.seasonStart(), mm = Integer.parseInt(month.substring(5));
        if (mm < 1 || mm > 12) {
            throw new IllegalArgumentException("bad month: " + month);   // 1~12가 아니면 아래 반복이 끝나지 않는다
        }
        List<Integer> out = new ArrayList<>();
        for (int k = s; ; k = k % 12 + 1) {
            out.add(k);
            if (k == mm) {
                return out;
            }
        }
    }

    private static String inList(List<Integer> xs) {
        return xs.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public List<BaselineRow> baseline(Country country, String id) {
        return jdbc.query("select * from timeseries.baseline where country = ? and region_id = ? order by m",
                BASELINE, country.name(), id);
    }

    public Optional<BaselineRow> baselineMonth(Country country, String id, int m) {
        return jdbc.query("select * from timeseries.baseline where country = ? and region_id = ? and m = ?",
                BASELINE, country.name(), id, m).stream().findFirst();
    }

    private static final RowMapper<SeriesRow> SERIES = (rs, i) -> new SeriesRow(
            rs.getString("month"), dbl(rs, "value"), dbl(rs, "raw_before"), rs.getString("source"), rs.getString("quality"),
            rs.getBoolean("estimated"), rs.getString("status"), integer(rs, "rank"), integer(rs, "n"),
            dbl(rs, "ratio_to_median"));

    /**
     * 지도용: 한 달치 모든 구역(나라·상위 단위·하위 구역)의 value·rank·ratio·평년·범위 한 번에.
     * 판정은 호출자가 하고, 하위 구역이 빌려 쓰는 상위 단위 판정도 같은 결과에서 찾는다.
     */
    public List<MapJudgeRow> mapJudge(Country country, String month) {
        int m = Integer.parseInt(month.substring(5));
        return jdbc.query("""
                select s.region_id, r.level, r.region_type, r.judge_unit, r.ref_id, s.value, s.rank, s.ratio_to_median,
                       b.median, b.band_low, b.band_high
                from timeseries.series s
                join timeseries.regions r on r.country = s.country and r.id = s.region_id
                left join timeseries.baseline b
                       on b.country = s.country and b.region_id = s.region_id and b.m = ?
                where s.country = ? and s.month = ?""",
                (rs, i) -> new MapJudgeRow(
                        rs.getString("region_id"), rs.getString("level"), rs.getString("region_type"),
                        rs.getString("judge_unit"), rs.getString("ref_id"), dbl(rs, "value"), integer(rs, "rank"),
                        dbl(rs, "ratio_to_median"), dbl(rs, "median"), dbl(rs, "band_low"), dbl(rs, "band_high")),
                m, country.name(), month);
    }

    /**
     * 올해 누적의 [아래 끝, 위 끝, 중앙값]: 기준 기간 해마다 "시즌 시작~같은 달" 누적 합을 구하고,
     * 월 판정과 같은 규칙(15~85 퍼센타일 ± 2√평년, 평년 < 1이면 √1. 파이프라인 BAND_Q·BAND_NOISE)을
     * 그 누적 합들에 적용한다. 누적 합이 3개 미만이면 null.
     * (월별 범위 끝을 그냥 더하면 해마다 들쭉날쭉한 달이 서로 상쇄되는 것을 무시해 범위가 지나치게 넓어진다)
     */
    public double[] ytdBand(Country country, String id, String month, int fromYear, int toYear) {
        Map<String, Double> v = new HashMap<>();
        jdbc.query("select month, value from timeseries.series where country = ? and region_id = ? and month >= ? and month <= ?",
                (RowCallbackHandler) rs -> {
                    Double x = dbl(rs, "value");
                    if (x != null) {
                        v.put(rs.getString("month"), x);
                    }
                }, country.name(), id, fromYear + "-01", toYear + "-12");
        int s = country.seasonStart();
        List<Integer> ms = seasonMonths(country, month);
        List<Double> sums = new ArrayList<>();
        for (int sy = fromYear - 1; sy <= toYear; sy++) {
            double sum = 0;
            boolean ok = true;
            for (int k : ms) {
                int yy = k >= s ? sy : sy + 1;
                Double x = yy < fromYear || yy > toYear ? null : v.get("%04d-%02d".formatted(yy, k));
                if (x == null) {
                    ok = false;
                    break;
                }
                sum += x;
            }
            if (ok) {
                sums.add(sum);
            }
        }
        if (sums.size() < 3) {
            return null;
        }
        Collections.sort(sums);
        double med = percentile(sums, 50), slack = 2 * Math.sqrt(Math.max(med, 1));
        return new double[]{Math.min(percentile(sums, 15), med - slack), Math.max(percentile(sums, 85), med + slack), med};
    }

    /** numpy.percentile 기본(선형 보간)과 같은 퍼센타일. xs는 정렬돼 있어야 한다 */
    static double percentile(List<Double> xs, double p) {
        double pos = (xs.size() - 1) * p / 100.0;
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return xs.get(lo) + (xs.get(hi) - xs.get(lo)) * (pos - lo);
    }

    private static final RowMapper<BaselineRow> BASELINE = (rs, i) -> new BaselineRow(
            rs.getInt("m"), dbl(rs, "median"), dbl(rs, "q25"), dbl(rs, "q75"), dbl(rs, "band_low"), dbl(rs, "band_high"));

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer integer(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private JsonNode tree(String json) {
        try {
            return json == null ? null : mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
