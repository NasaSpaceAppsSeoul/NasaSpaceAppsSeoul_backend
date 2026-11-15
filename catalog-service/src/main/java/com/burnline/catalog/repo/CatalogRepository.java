package com.burnline.catalog.repo;

import com.burnline.common.domain.Country;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** catalog 스키마 조회. 표 정의는 src/main/resources/db/migration. */
@Repository
@RequiredArgsConstructor
public class CatalogRepository {

    private static final String COLUMNS = """
            id, level, label_ko, name_en, province, parent_id, region_type, judge_unit, ref_province, basis,
            risk_months::text, risk_share, risk_from, peak_month, center::text, bbox::text, neighbors::text""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Optional<JsonNode> meta(Country country) {
        return jdbc.queryForList("select doc::text from catalog.meta where country = ?", String.class, country.name())
                .stream().findFirst().map(this::tree);
    }

    /** level·q·parent는 선택. q는 한글·영문 이름 부분 일치 (대소문자 무시) */
    public List<RegionRow> regions(Country country, String level, String q, String parent) {
        List<Object> args = new ArrayList<>(List.of(country.name()));
        StringBuilder sql = new StringBuilder("select " + COLUMNS + ", null as geometry from catalog.regions where country = ?");
        if (level != null) {
            sql.append(" and level = ?");
            args.add(level);
        }
        if (parent != null) {
            sql.append(" and parent_id = ?");
            args.add(parent);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" and (label_ko ilike ? or name_en ilike ? or (coalesce(province, '') || ' ' || label_ko) ilike ?)");
            String like = "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" order by sort_order");
        return jdbc.query(sql.toString(), mapper(), args.toArray());
    }

    public Optional<RegionRow> region(Country country, String id) {
        return jdbc.query("select " + COLUMNS + ", null as geometry from catalog.regions where country = ? and id = ?",
                mapper(), country.name(), id).stream().findFirst();
    }

    public List<RegionRow> regionsByIds(Country country, Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select " + COLUMNS + ", null as geometry from catalog.regions "
                        + "where country = ? and id = any(?) order by sort_order",
                mapper(), country.name(), ids.toArray(String[]::new));
    }

    /** /geo: 경계가 있는 행만 */
    public List<RegionRow> geometries(Country country, String level, String parent) {
        return jdbc.query("select " + COLUMNS + ", geometry::text as geometry from catalog.regions "
                        + "where country = ? and level = ? and geometry is not null "
                        + "and (?::text is null or parent_id = ?) order by sort_order",
                mapper(), country.name(), level, parent, parent);
    }

    private RowMapper<RegionRow> mapper() {
        return (rs, i) -> new RegionRow(
                rs.getString("id"), rs.getString("level"), rs.getString("label_ko"), rs.getString("name_en"),
                rs.getString("province"), rs.getString("parent_id"), rs.getString("region_type"),
                rs.getString("judge_unit"), rs.getString("ref_province"), rs.getString("basis"),
                tree(rs.getString("risk_months")), nullableDouble(rs, "risk_share"), rs.getString("risk_from"),
                nullableInt(rs, "peak_month"),
                tree(rs.getString("center")), tree(rs.getString("bbox")), tree(rs.getString("neighbors")),
                tree(rs.getString("geometry")));
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private JsonNode tree(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("bad json in catalog", e);
        }
    }
}
