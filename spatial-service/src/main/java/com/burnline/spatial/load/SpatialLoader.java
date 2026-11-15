package com.burnline.spatial.load;

import com.burnline.common.domain.Country;
import com.burnline.common.load.Loader;
import com.burnline.common.load.SourceFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 원천 → spatial 스키마.
 * <ul>
 *   <li>regions: output/{c}/regions.geojson (사실값) + boundaries/{c} 원본 경계 (배정·50 km 중심)</li>
 *   <li>hotspots: output/{c}/hotspots_clean.csv → 0.01° 칸 × 하루 하나, 화면 기준 위성만, 구역 배정</li>
 *   <li>region_month: calendar_by_region.json의 viirs 블록 (지도 색·순위 사본)</li>
 *   <li>events, events_by_region: 감지 묶음</li>
 *   <li>official_fires: external/ 공식 기록 3종</li>
 *   <li>landing: 보정 전 연간 화재 칸(MODIS ~2011, S-NPP 2012~), 공식 피해면적 연 합계, 단절 검정·감지율</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpatialLoader implements Loader {

    static final String VIIRS_START = "2012-02-01";
    static final String NOAA20_START = "2018-04-01";
    private static final int BATCH = 5000;

    private final SourceFiles files;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    @Override
    public boolean loaded(Country country) {
        Integer n = jdbc.queryForObject("select count(*) from spatial.data_version where country = ?",
                Integer.class, country.name());
        return n != null && n > 0;
    }

    @Override
    public void load(Country c) {
        JsonNode meta = files.output(c, "meta.json");
        JsonNode geo = files.output(c, "regions.geojson");
        JsonNode cal = files.output(c, "calendar_by_region.json");

        // ---- 구역
        Map<String, String> ko = c == Country.KOR ? SourceFiles.koreanDistrictNames() : Map.of();
        Map<String, JsonNode> props = new LinkedHashMap<>();
        geo.path("features").forEach(f -> props.put(f.path("properties").path("id").asText(), f.path("properties")));
        RegionIndex index = new RegionIndex(boundaryFeatures(c), props.keySet());
        Map<String, double[]> centers = index.centers();

        List<Object[]> regionRows = new ArrayList<>();
        JsonNode calRegions = cal.path("regions");
        props.forEach((id, p) -> {
            ObjectNode facts = mapper.createObjectNode();
            facts.set("annual", p.get("viirs_annual_fire_days"));
            facts.set("annual_all", p.get("viirs_annual_all_years"));
            facts.set("longterm", p.get("longterm"));
            facts.set("neighbors", p.get("neighbors"));
            facts.set("yrs_fire_by_month", calRegions.path(id).path("viirs").path("years_with_fire_by_month"));
            double[] ctr = centers.getOrDefault(id, new double[]{0, 0});
            regionRows.add(new Object[]{c.name(), id, p.path("name").asText(), ko.getOrDefault(id, p.path("name").asText()),
                    c.single() ? null : p.path("province").asText(null), json(new double[]{round4(ctr[0]), round4(ctr[1])}),
                    facts.toString()});
        });

        // ---- 시·군·구 월값
        List<Object[]> monthRows = new ArrayList<>();
        JsonNode months = cal.path("months");
        props.keySet().forEach(id -> {
            JsonNode v = calRegions.path(id).path("viirs");
            for (int i = 0; i < months.size(); i++) {
                JsonNode val = v.path("value").path(i);
                if (val.isMissingNode() || val.isNull()) {
                    continue;
                }
                JsonNode rk = v.path("rank").path(i), n = v.path("years_in_month").path(i);
                monthRows.add(new Object[]{c.name(), id, months.get(i).asText(), val.asDouble(),
                        rk.isNumber() ? rk.asInt() : null, n.isNumber() ? n.asInt() : null});
            }
        });

        // ---- 감지 점 (한 번 읽으며 화면용 점과 보정 전 연간 값을 함께)
        Hotspots hs = readHotspots(c, index);

        // ---- 감지 묶음
        List<Object[]> eventRows = new ArrayList<>();
        for (JsonNode e : files.output(c, "events.json")) {
            eventRows.add(new Object[]{c.name(), e.path("id").asInt(), date(e.path("start").asText()), date(e.path("end").asText()),
                    e.path("days").asInt(), e.path("cells").asInt(), e.path("peak_cells").asInt(), date(e.path("peak_date").asText()),
                    dbl(e.get("lat")), dbl(e.get("lon")), dbl(e.get("frp_mw")), e.path("regions").toString()});
        }
        JsonNode ebr = files.output(c, "events_by_region.json");
        List<Object[]> ebrRows = new ArrayList<>();
        JsonNode em = ebr.path("months");
        ebr.path("regions").properties().forEach(en -> {
            JsonNode r = en.getValue();
            for (int i = 0; i < em.size(); i++) {
                ebrRows.add(new Object[]{c.name(), en.getKey(), em.get(i).asText(), r.path("fire_days").path(i).asInt(),
                        r.path("peak_day_cells").path(i).asInt(), r.path("events").path(i).asInt(), r.path("big_events").path(i).asInt()});
            }
        });

        // ---- 공식 기록
        List<String[]> korDistricts = new ArrayList<>();
        if (c == Country.KOR) {
            props.forEach((id, p) -> korDistricts.add(new String[]{id, ko.getOrDefault(id, p.path("name").asText()),
                    p.path("province").asText()}));
        }
        List<OfficialFireReader.Fire> fires = OfficialFireReader.read(c, files.dir().resolve("external"), index, korDistricts);
        List<Object[]> fireRows = fires.stream().map(f -> new Object[]{c.name(), java.sql.Date.valueOf(f.date()),
                f.date().toString().substring(0, 7), f.place(), f.cause(), f.areaHa(), f.regionId(), f.source()}).toList();

        // ---- 랜딩 원자료
        ObjectNode landing = landing(c, meta, hs, fires);

        tx.executeWithoutResult(s -> {
            for (String t : List.of("regions", "hotspots", "region_month", "events", "events_by_region", "official_fires", "landing")) {
                jdbc.update("delete from spatial." + t + " where country = ?", c.name());
            }
            jdbc.batchUpdate("insert into spatial.regions(country, id, name_en, label_ko, province, center, facts) "
                    + "values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)", regionRows);
            batch("insert into spatial.region_month(country, region_id, month, value, rank, n) values (?, ?, ?, ?, ?, ?)", monthRows);
            batch("insert into spatial.hotspots(country, month, day, lon, lat, sensor, region_id) values (?, ?, ?, ?, ?, ?, ?)", hs.rows);
            batch("insert into spatial.events(country, id, start_date, end_date, days, cells, peak_cells, peak_date, lat, lon, frp_mw, regions) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", eventRows);
            batch("insert into spatial.events_by_region(country, region_id, month, fire_days, peak_day_cells, events, big_events) "
                    + "values (?, ?, ?, ?, ?, ?, ?)", ebrRows);
            batch("insert into spatial.official_fires(country, fire_date, month, place, cause, area_ha, region_id, source) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?)", fireRows);
            jdbc.update("insert into spatial.landing(country, doc) values (?, ?::jsonb)", c.name(), landing.toString());
            jdbc.update("""
                    insert into spatial.data_version(country, version, loaded_at) values (?, ?, now())
                    on conflict (country) do update set version = excluded.version, loaded_at = now()""",
                    c.name(), meta.path("generated").asText());
        });
        long matched = fires.stream().filter(f -> f.regionId() != null).count();
        log.info("spatial {}: {} regions, {} points ({} outside), {} events, {} official fires ({} matched)",
                c, regionRows.size(), hs.rows.size(), hs.outside, eventRows.size(), fires.size(), matched);
    }

    // ---------------------------------------------------------------- 경계

    private List<JsonNode> boundaryFeatures(Country c) {
        List<JsonNode> out = new ArrayList<>();
        files.boundaries(c, "geoBoundaries-%s-ADM2.geojson".formatted(c.name())).path("features").forEach(out::add);
        Path patch = files.dir().resolve("boundaries").resolve(c.name()).resolve("patch-%s-ADM2.geojson".formatted(c.name()));
        if (Files.exists(patch)) {
            files.boundaries(c, patch.getFileName().toString()).path("features").forEach(out::add);   // 한국: 영광군 보강
        }
        return out;
    }

    // ---------------------------------------------------------------- 감지 점

    /** 화면용 점 행과 보정 전 연간 화재 칸(위성별 칸 × 하루) */
    static final class Hotspots {
        final List<Object[]> rows = new ArrayList<>();
        final Map<Integer, Integer> modisYear = new TreeMap<>();
        final Map<Integer, Integer> snppYear = new TreeMap<>();
        int outside;
    }

    private Hotspots readHotspots(Country c, RegionIndex index) {
        Path csv = files.dir().resolve("output").resolve(c.name()).resolve("hotspots_clean.csv");
        Hotspots out = new Hotspots();
        // 화면용 칸×하루: 좌표는 첫 점, 구역은 그 칸·그날 점들이 가장 많이 들어간 구역 (파이프라인 ⑯ 묶음과 같은 규칙)
        Map<String, CellDay> shown = new LinkedHashMap<>();
        Set<String> raw = new HashSet<>();
        try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(in)) {
            for (CSVRecord r : parser) {
                String sensor = r.get("sensor");
                String date = r.get("acq_date");
                double lat = Double.parseDouble(r.get("latitude"));
                double lon = Double.parseDouble(r.get("longitude"));
                // 파이프라인과 같은 칸 키: (위도/0.01).round(), (경도/0.01).round() — 반올림은 짝수 쪽(rint)
                String cell = (long) Math.rint(lat / 0.01) + "_" + (long) Math.rint(lon / 0.01);
                int year = Integer.parseInt(date.substring(0, 4));

                // 보정 전 연간 값: MODIS(2012 전), S-NPP(2012~), 위성 × 칸 × 하루
                if (("MODIS".equals(sensor) && year < 2012) || ("VIIRS".equals(sensor) && year >= 2012)) {
                    if (raw.add(sensor + "|" + cell + "|" + date)) {
                        (year < 2012 ? out.modisYear : out.snppYear).merge(year, 1, Integer::sum);
                    }
                }
                // 화면용: 2012-02 전 MODIS, 2012-02~2018-03 S-NPP, 2018-04~ NOAA-20
                boolean display = ("MODIS".equals(sensor) && date.compareTo(VIIRS_START) < 0)
                        || ("VIIRS".equals(sensor) && date.compareTo(VIIRS_START) >= 0 && date.compareTo(NOAA20_START) < 0)
                        || ("N20".equals(sensor) && date.compareTo(NOAA20_START) >= 0);   // 2018-04~는 NOAA-20만: S-NPP를 섞으면 칸×하루가 30~60% 늘어 H와 어긋남
                if (!display) {
                    continue;
                }
                CellDay cd = shown.computeIfAbsent(cell + "|" + date, k -> new CellDay(date, lon, lat, sensor));
                cd.vote(index.locate(lon, lat), index);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + csv, e);
        }
        for (CellDay cd : shown.values()) {
            String region = cd.region();
            if (region == null) {
                out.outside++;
            }
            out.rows.add(new Object[]{c.name(), cd.date.substring(0, 7), Integer.parseInt(cd.date.substring(8, 10)),
                    cd.lon, cd.lat, cd.sensor, region});
        }
        return out;
    }

    /** 칸×하루 하나: 첫 점 좌표 + 점마다 들어간 구역 표 (가장 많은 구역, 같으면 경계 파일 순서가 앞선 구역) */
    static final class CellDay {
        final String date, sensor;
        final double lon, lat;
        private final Map<String, Integer> votes = new LinkedHashMap<>(2);
        private RegionIndex index;

        CellDay(String date, double lon, double lat, String sensor) {
            this.date = date;
            this.lon = lon;
            this.lat = lat;
            this.sensor = sensor;
        }

        void vote(String region, RegionIndex index) {
            this.index = index;
            votes.merge(region == null ? "" : region, 1, Integer::sum);
        }

        String region() {
            String best = null;
            int bestN = -1;
            for (var e : votes.entrySet()) {
                int n = e.getValue();
                if (n > bestN || (n == bestN && index.order(e.getKey()) < index.order(best))) {
                    best = e.getKey();
                    bestN = n;
                }
            }
            return best == null || best.isEmpty() ? null : best;
        }
    }

    // ---------------------------------------------------------------- 랜딩

    private ObjectNode landing(Country c, JsonNode meta, Hotspots hs, List<OfficialFireReader.Fire> fires) {
        ObjectNode d = mapper.createObjectNode();
        ArrayNode years = d.putArray("years"), before = d.putArray("before");
        for (int y = 2001; y <= 2025; y++) {
            years.add(y);
            before.add((y < 2012 ? hs.modisYear : hs.snppYear).getOrDefault(y, 0));
        }
        Map<Integer, Double> area = new TreeMap<>();
        fires.forEach(f -> area.merge(f.year(), f.areaHa(), Double::sum));   // 기관 집계 연도 (BC FIRE_YEAR)
        ObjectNode areaByYear = d.putObject("official_area_by_year");
        area.forEach((y, ha) -> {
            if (y >= 2001 && y <= 2025) {
                areaByYear.put(String.valueOf(y), Math.round(ha));
            }
        });
        d.set("break_test", meta.path("break_test"));
        JsonNode ev = meta.path("external_validation");
        d.set("detect_by_size", ev.path("detection_by_size"));
        d.set("kosis_annual", ev.path("kosis_annual").path("independent_break_test"));
        d.set("annual_records", ev.path("annual_records").path("independent_break_test"));
        d.set("national_risk", meta.path("regions").path("national_risk"));
        return d;
    }

    // ---------------------------------------------------------------- 도움

    private void batch(String sql, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i += BATCH) {
            jdbc.batchUpdate(sql, rows.subList(i, Math.min(rows.size(), i + BATCH)));
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.sql.Date date(String s) {
        return java.sql.Date.valueOf(s.substring(0, 10));
    }

    private static Double dbl(JsonNode n) {
        return n == null || !n.isNumber() ? null : n.asDouble();
    }

    private static double round4(double v) {
        return Math.round(v * 1e4) / 1e4;
    }
}
