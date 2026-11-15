package com.burnline.catalog.load;

import com.burnline.common.domain.Codes.Basis;
import com.burnline.common.domain.Codes.JudgeUnit;
import com.burnline.common.domain.Codes.Level;
import com.burnline.common.domain.Codes.RegionType;
import com.burnline.common.domain.Country;
import com.burnline.common.domain.Rules;
import com.burnline.common.load.Geo;
import com.burnline.common.load.Loader;
import com.burnline.common.load.SourceFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 원천 → catalog.regions / catalog.meta.
 * <ul>
 *   <li>dist: output/{c}/regions.geojson (시·군·구·카운티·지역구). 한국 한글 이름은 names/KOR_district_ko.csv</li>
 *   <li>prov: 한국만. output/KOR/calendar_by_province.json + boundaries/KOR/geoBoundaries-KOR-ADM1.geojson</li>
 *   <li>nat: meta.json의 regions.national_risk. CAL·BC 경계는 ADM1 (주 하나)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogLoader implements Loader {

    /** geoBoundaries KOR ADM1 이름 → 시·도 (calendar_by_province 키) */
    private static final Map<String, String> KOR_ADM1 = Map.ofEntries(
            Map.entry("Seoul", "서울"), Map.entry("Busan", "부산"), Map.entry("Daegu", "대구"),
            Map.entry("Incheon", "인천"), Map.entry("Gwangju", "광주"), Map.entry("Daejeon", "대전"),
            Map.entry("Ulsan", "울산"), Map.entry("Sejong", "세종"), Map.entry("Gyeonggi", "경기"),
            Map.entry("Gangwon", "강원"), Map.entry("North Chungcheong", "충북"),
            Map.entry("South Chungcheong", "충남"), Map.entry("North Jeolla", "전북"),
            Map.entry("South Jeolla", "전남"), Map.entry("North Gyeongsang", "경북"),
            Map.entry("South Gyeongsang", "경남"), Map.entry("Jeju", "제주"));

    private final SourceFiles files;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    @Override
    public boolean loaded(Country country) {
        Integer n = jdbc.queryForObject("select count(*) from catalog.data_version where country = ?",
                Integer.class, country.name());
        return n != null && n > 0;
    }

    @Override
    public void load(Country c) {
        JsonNode meta = files.output(c, "meta.json");
        ((com.fasterxml.jackson.databind.node.ObjectNode) meta).put("generated_at", files.generatedAt(c, meta));
        JsonNode geo = files.output(c, "regions.geojson");
        Map<String, String> koNames = c == Country.KOR ? SourceFiles.koreanDistrictNames() : Map.of();

        List<Row> rows = new ArrayList<>();
        List<Row> dists = districts(c, geo, koNames);
        if (!c.single()) {
            rows.addAll(provinces(c, dists));
        }
        rows.add(0, nation(c, meta, dists));
        rows.addAll(dists);
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).sortOrder = i;
        }

        tx.executeWithoutResult(s -> {
            jdbc.update("delete from catalog.regions where country = ?", c.name());
            jdbc.batchUpdate("""
                    insert into catalog.regions(country, id, level, label_ko, name_en, province, parent_id,
                      region_type, judge_unit, ref_province, basis, risk_months, risk_share, risk_from, peak_month,
                      center, bbox, neighbors, sort_order, props, geometry)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb)""",
                    rows.stream().map(r -> r.params(c, this::json)).toList());
            jdbc.update("delete from catalog.meta where country = ?", c.name());
            jdbc.update("insert into catalog.meta(country, doc) values (?, ?::jsonb)", c.name(), meta.toString());
            jdbc.update("""
                    insert into catalog.data_version(country, version, loaded_at) values (?, ?, now())
                    on conflict (country) do update set version = excluded.version, loaded_at = now()""",
                    c.name(), meta.path("generated").asText());
        });
        log.info("catalog {}: {} regions ({} districts)", c, rows.size(), dists.size());
    }

    // ---------------------------------------------------------------- 시·군·구
    private List<Row> districts(Country c, JsonNode geo, Map<String, String> koNames) {
        List<Row> out = new ArrayList<>();
        for (JsonNode f : geo.path("features")) {
            JsonNode p = f.path("properties");
            JsonNode geometry = f.get("geometry");
            JsonNode fallback = p.path("fallback");
            Row r = new Row();
            r.id = p.path("id").asText();
            r.level = Level.dist;
            r.nameEn = p.path("name").asText();
            r.labelKo = c == Country.KOR
                    ? koNames.getOrDefault(r.id, r.nameEn)
                    : p.path("name_ko").asText(r.nameEn);
            r.province = c.single() ? null : p.path("province").asText();
            r.parentId = c.single() ? c.name() : r.province;
            r.regionType = Rules.regionType(p.path("sufficiency").asText());
            r.judgeUnit = r.regionType == RegionType.sufficient ? JudgeUnit.SELF
                    : Rules.judgeUnit(fallback.path("ref_from").asText(null));
            if (r.judgeUnit != JudgeUnit.SELF) {
                // CAL·BC는 상위가 주 하나 → 주 전체(나라 ID)로 판정
                r.refProvince = c.single() ? c.name() : fallback.path("ref_province").asText(null);
            }
            r.basis = Basis.viirs_2012;
            r.riskMonths = p.get("risk_months");
            r.riskShare = p.path("risk_share").isNumber() ? p.get("risk_share").asDouble() : null;
            r.peakMonth = p.path("peak_month").isInt() ? p.get("peak_month").asInt() : null;
            r.riskFrom = Rules.riskFrom(p.path("risk_from").asText(null));
            r.center = Geo.centroid(geometry);
            r.bbox = Geo.bbox(geometry);
            p.path("neighbors").forEach(n -> r.neighbors.add(n.asText()));
            r.props = p;
            r.geometry = Geo.roundCoordinates(geometry);
            out.add(r);
        }
        out.sort(Comparator.comparing((Row r) -> r.province == null ? "" : r.province).thenComparing(r -> r.labelKo));
        return out;
    }

    // ---------------------------------------------------------------- 상위 단위 (한국 시·도, 그리스 광역, 칠레 주)
    private List<Row> provinces(Country c, List<Row> dists) {
        JsonNode blocks = files.output(c, "calendar_by_province.json").path("provinces");
        Map<String, JsonNode> adm1 = new LinkedHashMap<>();
        Map<String, String[]> adm1Names = new LinkedHashMap<>();   // 상위 단위 ID → [한국어 이름, 영어 이름]
        String adm1File = "geoBoundaries-%s-ADM1.geojson".formatted(c.name());
        for (JsonNode f : files.boundaries(c, adm1File).path("features")) {
            JsonNode p = f.path("properties");
            String shapeName = p.path("shapeName").asText();
            // 상위 단위 ID = calendar_by_province 키. KOR은 한국어 시·도(KOR_ADM1로 변환), 기타는 shapeName 그대로.
            // 화면 이름은 ADM1 파일의 name_ko·name_en (그리스·칠레). 없으면 ID를 그대로 쓴다
            String key = c == Country.KOR ? KOR_ADM1.get(shapeName) : shapeName;
            if (key != null) {
                adm1.put(key, f.get("geometry"));
                adm1Names.put(key, new String[]{p.path("name_ko").asText(key),
                        p.path("name_en").asText(c == Country.KOR ? shapeName : key)});
            }
        }
        Map<String, String> distProvince = new LinkedHashMap<>();
        dists.forEach(d -> distProvince.put(d.id, d.province));

        Map<String, String> provKo = new LinkedHashMap<>();
        dists.forEach(d -> {
            String pko = d.props != null ? d.props.path("province_ko").asText(null) : null;
            if (pko != null && !pko.isEmpty() && d.province != null) {
                provKo.putIfAbsent(d.province, pko);
            }
        });

        Map<String, Row> provs = new LinkedHashMap<>();
        blocks.properties().forEach(e -> {
            String name = e.getKey();
            JsonNode b = e.getValue();
            List<Row> members = dists.stream().filter(d -> name.equals(d.province)).toList();
            Row r = new Row();
            r.id = name;
            r.level = Level.prov;
            String[] names = adm1Names.getOrDefault(name, new String[]{name, name});
            r.labelKo = provKo.getOrDefault(name, names[0]);   // 경북, 아티키, 비오비오
            r.nameEn = names[1];    // North Gyeongsang, Attica, Biobío
            r.province = name;
            r.parentId = c.name();
            r.regionType = Rules.regionType(b.path("sufficiency").asText());
            r.basis = Rules.basis(b.path("basis").asText());
            r.riskMonths = b.get("risk_months");
            r.riskShare = b.path("risk_share").isNumber() ? b.get("risk_share").asDouble() : null;
            r.riskFrom = JudgeUnit.SELF.value();   // 시·도 위험 시기는 늘 자기 값
            r.center = new double[]{
                    Geo.round3(members.stream().mapToDouble(m -> m.center[0]).average().orElse(0)),
                    Geo.round3(members.stream().mapToDouble(m -> m.center[1]).average().orElse(0))};
            double[] box = null;
            for (Row m : members) {
                box = Geo.union(box, m.bbox);
            }
            r.bbox = box;
            TreeSet<String> near = new TreeSet<>();
            members.forEach(m -> m.neighbors.forEach(n -> {
                String pv = distProvince.get(n);
                if (pv != null && !pv.equals(name)) {
                    near.add(pv);
                }
            }));
            r.neighbors.addAll(near);
            r.props = b.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) r.props).remove(List.of(
                    "value", "median_by_month", "q25_by_month", "q75_by_month", "band_low_by_month",
                    "band_high_by_month", "rank", "years_in_month", "ratio_to_median"));
            JsonNode g = adm1.get(name);
            r.geometry = g == null ? null : Geo.roundCoordinates(g);
            provs.put(name, r);
        });

        // 부족한 시·도는 중심점이 가장 가까운 "충분" 시·도의 판정을 빌린다
        List<Row> sufficient = provs.values().stream().filter(p -> p.regionType == RegionType.sufficient).toList();
        for (Row p : provs.values()) {
            if (p.regionType == RegionType.sufficient) {
                p.judgeUnit = JudgeUnit.SELF;
            } else {
                p.judgeUnit = JudgeUnit.NEARBY_PROVINCE;
                p.refProvince = sufficient.stream().min(Comparator.comparingDouble(s -> Geo.km(s.center, p.center)))
                        .map(s -> s.id).orElse(c.name());
            }
        }
        return new ArrayList<>(provs.values());
    }

    // ---------------------------------------------------------------- 나라 (주)
    private Row nation(Country c, JsonNode meta, List<Row> dists) {
        Row r = new Row();
        r.id = c.nationalId();
        r.level = Level.nat;
        r.labelKo = c.labelKo();
        r.nameEn = c.nameEn();
        r.regionType = RegionType.sufficient;
        r.judgeUnit = JudgeUnit.SELF;
        r.basis = Basis.harmonized_25y;
        JsonNode risk = meta.path("regions").path("national_risk");
        r.riskMonths = risk.get("months");
        r.riskShare = risk.path("share").isNumber() ? risk.get("share").asDouble() : null;
        r.peakMonth = risk.path("peak_month").isInt() ? risk.get("peak_month").asInt() : null;   // 옛 패키지에는 없음 → null
        r.riskFrom = JudgeUnit.SELF.value();
        double[] box = null;
        for (Row d : dists) {
            box = Geo.union(box, d.bbox);
        }
        r.bbox = box;
        r.center = box == null ? null : new double[]{Geo.round3((box[0] + box[2]) / 2), Geo.round3((box[1] + box[3]) / 2)};
        r.props = mapper.createObjectNode();
        if (c.single()) {   // 주 경계 하나
            JsonNode f = files.boundaries(c, "geoBoundaries-%s-ADM1.geojson".formatted(c)).path("features").path(0);
            r.geometry = f.has("geometry") ? Geo.roundCoordinates(f.get("geometry")) : null;
        }
        return r;
    }

    private String json(Object value) {
        try {
            return value == null ? null : mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 적재용 한 행 */
    private static final class Row {
        String id;
        Level level;
        String labelKo;
        String nameEn;
        String province;
        String parentId;
        RegionType regionType;
        JudgeUnit judgeUnit;
        String refProvince;
        Basis basis;
        JsonNode riskMonths;
        Double riskShare;
        String riskFrom;
        Integer peakMonth;   // 위험 시기의 정점 달 (나라: meta national_risk.peak_month, 하위 구역: regions.geojson peak_month)
        double[] center;
        double[] bbox;
        final List<String> neighbors = new ArrayList<>();
        int sortOrder;
        JsonNode props;
        JsonNode geometry;

        Object[] params(Country c, java.util.function.Function<Object, String> json) {
            double[] box = bbox == null ? null : new double[]{
                    Geo.round3(bbox[0]), Geo.round3(bbox[1]), Geo.round3(bbox[2]), Geo.round3(bbox[3])};
            return new Object[]{c.name(), id, level.name(), labelKo, nameEn, province, parentId,
                    regionType.name(), judgeUnit.value(), refProvince, basis.name(),
                    riskMonths == null || riskMonths.isNull() ? "[]" : riskMonths.toString(), riskShare, riskFrom, peakMonth,
                    json.apply(center), json.apply(box), json.apply(neighbors), sortOrder,
                    props == null ? "{}" : props.toString(), geometry == null ? null : geometry.toString()};
        }
    }
}
