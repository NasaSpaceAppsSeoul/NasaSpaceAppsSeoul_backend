package com.burnline.spatial.service;

import com.burnline.common.domain.Country;
import com.burnline.common.error.ApiException;
import com.burnline.common.load.SourceFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * spatial 조회. 명세 3장 /map·/events·/official-fires와, insight가 부르는 내부 조회.
 * 판정(칩·평년 비교)은 하지 않는다 — timeseries 담당.
 */
@Service
@RequiredArgsConstructor
public class SpatialService {

    /** 명세: 한 달 점이 이보다 많으면 고르게 뽑은 만큼만 보낸다 */
    public static final int POINTS_MAX = 4000;
    /** 50 km 안 첫 감지 */
    static final double NEAR_KM = 50;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SourceFiles files;
    /** 나라별 "최근 달"의 첫 달 (이 달부터 partial·nrt). 원천 파일을 한 번만 읽는다 */
    private final Map<Country, String> recentFrom = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- /map

    public Map<String, Object> map(Country c, String month, String parent) {
        checkMonth(c, month);
        Set<String> ids = parent == null ? null : childrenOf(c, parent);
        List<Integer> bins = bins(c);

        List<Map<String, Object>> regions = new ArrayList<>();
        if (month.compareTo("2012-02") < 0) {
            // 명세 5장 PRE_2012_DISTRICT: 2012-02 전 시·군·구 값은 없다 → 구역마다 null (지도 회색)
            for (String id : ids == null ? regionsById(c).keySet() : new java.util.TreeSet<>(ids)) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("id", id);
                x.put("value", null);
                x.put("map_bin", null);
                x.put("reason", "PRE_2012_DISTRICT");
                regions.add(x);
            }
        }
        jdbc.query("select region_id, value from spatial.region_month where country = ? and month = ? order by region_id",
                rs -> {
                    String id = rs.getString(1);
                    if (ids != null && !ids.contains(id)) {
                        return;
                    }
                    double v = rs.getDouble(2);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", id);
                    m.put("value", v);
                    m.put("map_bin", mapBin(v, bins));
                    regions.add(m);
                }, c.name(), month);

        List<Object[]> pts = new ArrayList<>();
        jdbc.query("select lon, lat, day, region_id from spatial.hotspots where country = ? and month = ?", rs -> {
            String rid = rs.getString(4);
            if (ids == null || ids.contains(rid)) {
                pts.add(new Object[]{round3(rs.getDouble(1)), round3(rs.getDouble(2)), rs.getInt(3), rid});
            }
        }, c.name(), month);
        int count = pts.size();
        List<Object[]> shown = sample(pts, month);

        Map<String, Object> points = new LinkedHashMap<>();
        points.put("sensor", sensorOf(month));
        points.put("count", count);
        points.put("shown", shown.size());
        points.put("items", shown);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("parent", parent == null ? c.name() : parent);
        out.put("regions", regions);
        out.put("points", points);
        return out;
    }

    /** 고정 시드로 고르게 뽑고 날짜 순으로 (같은 달은 늘 같은 표본) */
    static List<Object[]> sample(List<Object[]> pts, String month) {
        if (pts.size() <= POINTS_MAX) {
            return pts;
        }
        List<Object[]> copy = new ArrayList<>(pts);
        Collections.shuffle(copy, new Random(month.hashCode()));
        List<Object[]> s = new ArrayList<>(copy.subList(0, POINTS_MAX));
        s.sort(Comparator.comparingInt(p -> (Integer) p[2]));
        return s;
    }

    static String sensorOf(String month) {
        return month.compareTo("2012-02") < 0 ? "MODIS" : month.compareTo("2018-04") < 0 ? "S-NPP" : "NOAA-20";
    }

    /** 명세 2장 map_bin: 0 / 1 / 2 / 3 / 4 (경계는 나라별 /meta.labels.bins) */
    static int mapBin(double v, List<Integer> bins) {
        if (v <= 0) {
            return 0;
        }
        if (v < bins.get(0)) {
            return 1;
        }
        if (v < bins.get(1)) {
            return 2;
        }
        return v < bins.get(2) ? 3 : 4;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> bins(Country c) {
        return (List<Integer>) c.labels().get("bins");
    }

    // ---------------------------------------------------------------- /events

    public Map<String, Object> events(Country c, String region, String from, String to) {
        if (from != null) {
            checkMonth(c, from);   // 범위 밖 달은 /map과 같이 MONTH_OUT_OF_RANGE
        }
        if (to != null) {
            checkMonth(c, to);
        }
        LocalDate start = from == null ? LocalDate.of(2018, 4, 1) : YearMonth.parse(from).atDay(1);
        LocalDate end = to == null ? LocalDate.of(2100, 1, 1) : YearMonth.parse(to).atEndOfMonth();
        if (region != null) {
            requireRegion(c, region);
        }
        String sql = "select id, start_date, end_date, days, cells, peak_cells, peak_date, lat, lon, frp_mw, regions "
                + "from spatial.events where country = ? and start_date <= ? and end_date >= ?"
                + (region == null ? "" : " and regions @> jsonb_build_array(?::text)") + " order by start_date, id";
        Object[] args = region == null ? new Object[]{c.name(), end, start} : new Object[]{c.name(), end, start, region};
        List<Map<String, Object>> items = jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getInt("id"));
            m.put("start", rs.getDate("start_date").toString());
            m.put("end", rs.getDate("end_date").toString());
            m.put("days", rs.getInt("days"));
            m.put("cells", rs.getInt("cells"));
            m.put("peak_cells", rs.getInt("peak_cells"));
            m.put("peak_date", rs.getDate("peak_date").toString());
            m.put("lat", rs.getObject("lat"));
            m.put("lon", rs.getObject("lon"));
            m.put("frp_mw", rs.getObject("frp_mw"));
            m.put("regions", tree(rs.getString("regions")));
            return m;
        }, args);
        return Map.of("items", items);
    }

    // ---------------------------------------------------------------- /official-fires

    public Map<String, Object> officialFires(Country c, String region, String month) {
        if (region == null && month == null) {
            throw ApiException.invalidParam("region or month is required");
        }
        if (region != null) {
            requireRegion(c, region);
        }
        StringBuilder sql = new StringBuilder("select fire_date, place, cause, area_ha, region_id, source from spatial.official_fires where country = ?");
        List<Object> args = new ArrayList<>(List.of(c.name()));
        if (region != null) {
            sql.append(" and region_id = ?");
            args.add(region);
        }
        if (month != null) {
            sql.append(" and month = ?");
            args.add(month);
        }
        sql.append(" order by area_ha desc, fire_date desc");
        List<Map<String, Object>> items = jdbc.query(sql.toString(), (rs, i) -> fire(rs.getDate(1).toString(), rs.getString(2),
                rs.getString(3), rs.getDouble(4), rs.getString(5)), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", c.labels().get("official"));
        out.put("count", items.size());
        out.put("items", items);
        return out;
    }

    private static Map<String, Object> fire(String date, String place, String cause, double ha, String region) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date);
        m.put("place", place);
        m.put("cause", cause);
        m.put("area_ha", Math.round(ha * 100) / 100.0);
        m.put("region_id", region);
        return m;
    }

    // ---------------------------------------------------------------- 내부: 인사이트 달 상세

    /**
     * 인사이트 카드 2·3·5·7에 쓰는 이 구역·이 달의 사실값. 판정·평년은 들어 있지 않다.
     * 파이프라인 tools/mockup_extract.py build()와 같은 계산.
     */
    public Map<String, Object> monthDetail(Country c, String regionId, String month) {
        checkMonth(c, month);
        Map<String, Object> region = requireRegion(c, regionId);
        double[] center = (double[]) region.get("center");
        int m = Integer.parseInt(month.substring(5));

        // 이 달 감지 점·일별 칸
        List<Object[]> points = new ArrayList<>();
        Map<Integer, Integer> daily = new TreeMap<>();
        jdbc.query("select lon, lat, day from spatial.hotspots where country = ? and month = ? and region_id = ? order by day",
                rs -> {
                    int d = rs.getInt(3);
                    points.add(new Object[]{round3(rs.getDouble(1)), round3(rs.getDouble(2)), d});
                    daily.merge(d, 1, Integer::sum);
                }, c.name(), month, regionId);

        // 가장 넓게 탄 날 (이 구역 하루 최대)
        Integer dmaxDay = null;
        int dmax = 0;
        for (var e : daily.entrySet()) {
            if (e.getValue() > dmax) {
                dmax = e.getValue();
                dmaxDay = e.getKey();
            }
        }

        // 재발 격자: 0.02° 칸 중 2012-02~2025-12에 불이 있었던 칸과 그 해 수
        Map<String, Set<Integer>> grid = new HashMap<>();
        jdbc.query("select lon, lat, month from spatial.hotspots where country = ? and region_id = ? and month >= '2012-02' and month <= '2025-12'",
                rs -> {
                    String key = (long) Math.rint(rs.getDouble(2) / 0.02) + "_" + (long) Math.rint(rs.getDouble(1) / 0.02);
                    grid.computeIfAbsent(key, k -> new HashSet<>()).add(Integer.parseInt(rs.getString(3).substring(0, 4)));
                }, c.name(), regionId);
        List<Map<String, Object>> rec = new ArrayList<>();
        grid.forEach((k, ys) -> {
            String[] p = k.split("_");
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("lat", round3(Long.parseLong(p[0]) * 0.02));
            g.put("lon", round3(Long.parseLong(p[1]) * 0.02));
            g.put("y", ys.size());
            rec.add(g);
        });

        // 50 km 안 첫 감지 (이 구역 밖 점, 중심 = 원본 경계 꼭짓점 평균)
        double kmLon = kmPerLonDegree(c, center[1]);
        int[] near = {0, Integer.MAX_VALUE};
        jdbc.query("select lon, lat, day from spatial.hotspots where country = ? and month = ? and (region_id is null or region_id <> ?)",
                rs -> {
                    double d = Math.hypot((rs.getDouble(1) - center[0]) * kmLon, (rs.getDouble(2) - center[1]) * 111.0);
                    if (d <= NEAR_KM) {
                        near[0]++;
                        near[1] = Math.min(near[1], rs.getInt(3));
                    }
                }, c.name(), month, regionId);
        Map<String, Object> near50 = new LinkedHashMap<>();
        near50.put("n", near[0]);
        near50.put("first", near[0] == 0 ? null : near[1]);

        // 감지 묶음 (NOAA-20, 2018-04~)
        Map<String, Object> clusters = jdbc.query("select fire_days, peak_day_cells, events, big_events from spatial.events_by_region "
                + "where country = ? and region_id = ? and month = ?", rs -> {
            if (!rs.next()) {
                return null;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("fire_days", rs.getInt(1));
            x.put("peak_day_cells", rs.getInt(2));
            x.put("events", rs.getInt(3));
            x.put("big_events", rs.getInt(4));
            return x;
        }, c.name(), regionId, month);
        YearMonth ym = YearMonth.parse(month);
        Map<String, Object> mainCluster = jdbc.query("select start_date, end_date, days, cells, peak_cells, peak_date, regions from spatial.events "
                + "where country = ? and regions @> jsonb_build_array(?::text) and start_date <= ? and end_date >= ? "
                + "order by cells desc, id limit 1", rs -> {
            if (!rs.next()) {
                return null;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("start", rs.getDate(1).toString());
            x.put("end", rs.getDate(2).toString());
            x.put("days", rs.getInt(3));
            x.put("cells", rs.getInt(4));
            x.put("peak_cells", rs.getInt(5));
            x.put("peak_date", rs.getDate(6).toString());
            x.put("regions", tree(rs.getString(7)));
            return x;
        }, c.name(), regionId, ym.atEndOfMonth(), ym.atDay(1));
        if (mainCluster != null) {   // 걸친 구역 이름 (사이트: "Butte · Plumas에 걸침")
            Map<String, Map<String, Object>> regs = regionsById(c);
            List<Map<String, Object>> named = new ArrayList<>();
            ((JsonNode) mainCluster.get("regions")).forEach(n -> {
                Map<String, Object> r = regs.get(n.asText());
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("id", n.asText());
                x.put("label_ko", r == null ? null : r.get("label_ko"));
                named.add(x);
            });
            mainCluster.put("regions", named);
            // 이 구역에서 클러스터 기간 안에 감지된 날짜 목록 (프론트 달력 밑줄용)
            List<Integer> regionDays = daily.keySet().stream()
                    .filter(d -> {
                        String date = ym.atDay(d).toString();
                        return date.compareTo((String) mainCluster.get("start")) >= 0
                                && date.compareTo((String) mainCluster.get("end")) <= 0;
                    })
                    .sorted()
                    .toList();
            mainCluster.put("region_days", regionDays);
        }

        // 공식 기록
        List<Map<String, Object>> official = jdbc.query("select fire_date, place, cause, area_ha, region_id from spatial.official_fires "
                        + "where country = ? and region_id = ? and month = ? order by area_ha desc, fire_date desc",
                (rs, i) -> fire(rs.getDate(1).toString(), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getString(5)),
                c.name(), regionId, month);

        JsonNode facts = (JsonNode) region.get("facts");
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("annual", facts.get("annual"));
        f.put("annual_all", facts.get("annual_all"));
        f.put("longterm", facts.get("longterm"));
        JsonNode yf = facts.path("yrs_fire_by_month").path(m - 1);
        f.put("yrs_fire", yf.isNumber() ? yf.asInt() : null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", regionId);
        out.put("month", month);
        out.put("data_version", version(c));
        out.put("center", center);
        out.put("facts", f);
        out.put("daily", daily);
        out.put("fire_days", daily.size());
        out.put("dmax", dmax);
        out.put("dmax_date", dmaxDay == null ? null : ym.atDay(dmaxDay).toString());
        out.put("main_cluster", mainCluster);
        out.put("clusters", clusters);
        out.put("near50", near50);
        out.put("points", points);
        out.put("rec", rec);
        out.put("official", official);
        out.put("detect_rate", detectRate(c, regionId));
        return out;
    }

    /**
     * 명세 4장 카드 5 감지율 한 줄: 이 구역 공식 산불 기록(NOAA-20 시기, 2018-04~) 중
     * 같은 구역에서 발생일부터 2일 안에 화재 칸이 있었던 비율. 사이트 랜딩 감지율과 같은 규칙이다.
     */
    public Map<String, Object> detectRate(Country c, String regionId) {
        Map<String, Object> r = jdbc.queryForMap("""
                select count(*) as n,
                       count(*) filter (where exists (
                           select 1 from spatial.hotspots h
                            where h.country = f.country and h.region_id = f.region_id
                              and h.month in (to_char(f.fire_date, 'YYYY-MM'), to_char(f.fire_date + 2, 'YYYY-MM'))
                              and make_date(substr(h.month, 1, 4)::int, substr(h.month, 6, 2)::int, h.day)
                                  between f.fire_date and f.fire_date + 2)) as detected
                  from spatial.official_fires f
                 where f.country = ? and f.region_id = ? and f.fire_date >= date '2018-04-01'""", c.name(), regionId);
        long n = ((Number) r.get("n")).longValue();
        long hit = ((Number) r.get("detected")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("n", n);
        out.put("detected", hit);
        out.put("rate", n == 0 ? null : Math.round(hit * 100.0 / n) / 100.0);
        out.put("since", "2018-04");
        out.put("window_days", 2);
        return out;
    }

    // ---------------------------------------------------------------- 내부: 목록 카드 TOP3

    /**
     * 나라·시·도 화면: 이번 달 H > 0인 하위 구역을 같은 달 순위 → H 순으로 3곳.
     * 시·군·구 화면(parent = 시·군·구 ID): 맞닿은 지역을 H가 많은 순으로 (자기 포함).
     */
    public Map<String, Object> top(Country c, String month, String parent) {
        checkMonth(c, month);
        Map<String, Map<String, Object>> regs = regionsById(c);
        List<Map<String, Object>> items = new ArrayList<>();
        boolean distView = parent != null && regs.containsKey(parent);
        Set<String> pool;
        if (distView) {
            // 명세 3장 #9: 시·군·구면 맞닿은 지역만 H 순 (자기 자신은 뺀다)
            pool = new java.util.LinkedHashSet<>();
            ((JsonNode) regs.get(parent).get("facts")).path("neighbors").forEach(n -> pool.add(n.asText()));
        } else {
            pool = parent == null || parent.equals(c.name()) ? regs.keySet() : childrenOf(c, parent);
        }
        jdbc.query("select region_id, value, rank, n from spatial.region_month where country = ? and month = ?", rs -> {
            String id = rs.getString(1);
            if (!pool.contains(id)) {
                return;
            }
            double v = rs.getDouble(2);
            if (!distView && v <= 0) {
                return;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            Map<String, Object> r = regs.get(id);
            x.put("id", id);
            x.put("label_ko", c.single() || distView ? r.get("label_ko") : r.get("province") + " " + r.get("label_ko"));
            x.put("value", v);
            x.put("rank", v > 0 ? (Integer) rs.getObject(3) : null);
            x.put("n", rs.getObject(4));
            items.add(x);
        }, c.name(), month);
        if (distView) {
            items.sort(Comparator.comparingDouble((Map<String, Object> x) -> -(Double) x.get("value")));
        } else {
            items.sort(Comparator.comparingInt((Map<String, Object> x) -> x.get("rank") == null ? 99 : (Integer) x.get("rank"))
                    .thenComparingDouble(x -> -(Double) x.get("value")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("parent", parent == null ? c.name() : parent);
        out.put("data_version", version(c));
        out.put("items", distView ? items : items.subList(0, Math.min(3, items.size())));
        out.put("recent", recent(c, month));   // 내부용: insight가 캐시 시간을 정하고 응답에서 뺀다
        return out;
    }

    // ---------------------------------------------------------------- 내부: 랜딩 원자료

    public JsonNode landing(Country c) {
        List<String> docs = jdbc.queryForList("select doc::text from spatial.landing where country = ?", String.class, c.name());
        if (docs.isEmpty()) {
            throw ApiException.countryNotReady(c.name());
        }
        com.fasterxml.jackson.databind.node.ObjectNode d = (com.fasterxml.jackson.databind.node.ObjectNode) tree(docs.get(0));
        d.put("data_version", version(c));
        return d;
    }

    // ---------------------------------------------------------------- 공통

    public String version(Country c) {
        return jdbc.queryForList("select version from spatial.data_version where country = ?", String.class, c.name())
                .stream().findFirst().orElse("unknown");
    }

    static final String FIRST_MONTH = "2001-01";

    /**
     * 명세 1장 캐시: 최근 달(partial·nrt)이 든 응답은 짧게. A의 status 규칙(Rules.status)과 같은 기준이다.
     * NOAA-20 정식 아카이브(SP)가 끝난 다음 달부터 nrt, 데이터 마지막 달은 진행 중(partial)일 수 있어 함께 최근으로 본다.
     * month가 null이면(기간 전체) 최근 달이 들 수 있어 true.
     */
    public boolean recent(Country c, String month) {
        return month == null || month.compareTo(recentFrom(c)) >= 0;
    }

    String recentFrom(Country c) {
        return recentFrom.computeIfAbsent(c, k -> {
            String hi = jdbc.queryForObject("select max(month) from spatial.hotspots where country = ?", String.class, k.name());
            if (hi == null) {
                throw ApiException.countryNotReady(k.name());
            }
            String afterSp = files.noaa20ArchiveEnd(k).map(d -> YearMonth.from(d).plusMonths(1).toString()).orElse(hi);
            return afterSp.compareTo(hi) < 0 ? afterSp : hi;
        });
    }

    private void checkMonth(Country c, String month) {
        Map<String, Object> r = jdbc.queryForMap("select min(month) lo, max(month) hi from spatial.hotspots where country = ?", c.name());
        String lo = (String) r.get("lo"), hi = (String) r.get("hi");
        if (lo == null) {
            throw ApiException.countryNotReady(c.name());
        }
        // 한국 MODIS 점은 2000-11부터 있지만 서비스 기간은 2001-01부터 (A /series·/judgement와 같은 범위)
        if (lo.compareTo(FIRST_MONTH) < 0) {
            lo = FIRST_MONTH;
        }
        if (month.compareTo(lo) < 0 || month.compareTo(hi) > 0) {
            throw ApiException.monthOutOfRange(month, lo, hi);
        }
    }

    /** 나라 ID면 전체, 한국 시·도 이름이면 그 시·도의 시·군·구 */
    private Set<String> childrenOf(Country c, String parent) {
        if (parent.equals(c.name())) {
            return new HashSet<>(regionsById(c).keySet());
        }
        Set<String> ids = new HashSet<>(jdbc.queryForList("select id from spatial.regions where country = ? and province = ?",
                String.class, c.name(), parent));
        if (ids.isEmpty()) {
            throw ApiException.regionNotFound(c.name(), parent);
        }
        return ids;
    }

    private Map<String, Object> requireRegion(Country c, String id) {
        Map<String, Object> r = regionsById(c).get(id);
        if (r == null) {
            throw ApiException.regionNotFound(c.name(), id);
        }
        return r;
    }

    private Map<String, Map<String, Object>> regionsById(Country c) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        jdbc.query("select id, label_ko, province, center::text, facts::text from spatial.regions where country = ? order by id", rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("label_ko", rs.getString(2));
            r.put("province", rs.getString(3));
            JsonNode ctr = tree(rs.getString(4));
            r.put("center", new double[]{ctr.get(0).asDouble(), ctr.get(1).asDouble()});
            r.put("facts", tree(rs.getString(5)));
            out.put(rs.getString(1), r);
        }, c.name());
        return out;
    }

    private JsonNode tree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /**
     * 경도 1°의 km. 파이프라인 KM_LON과 같은 값을 쓰고, 남북으로 긴 칠레는 구역 중심 위도로 계산한다 (111.32·cos 위도).
     */
    static double kmPerLonDegree(Country c, double lat) {
        return switch (c) {
            case KOR, CAL -> 88.8;
            case BC -> 66.8;
            case GRC -> 87.1;
            case NSW -> 93.4;
            case CHL -> 111.32 * Math.cos(Math.toRadians(lat));
        };
    }
}
