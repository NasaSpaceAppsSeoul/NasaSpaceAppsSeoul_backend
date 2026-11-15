package com.burnline.common.version;

import com.burnline.common.config.BurnlineProperties;
import com.burnline.common.domain.Country;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * spring-boot-starter-jdbc가 있는 서비스에서만 등록된다.
 * 표 이름은 burnline.data.version-table (서비스마다 자기 스키마: catalog.data_version, timeseries.data_version)
 */
@Component
@ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
public class JdbcDataVersionSource implements DataVersionSource {

    private final JdbcTemplate jdbc;
    private final String sql;

    public JdbcDataVersionSource(JdbcTemplate jdbc, BurnlineProperties props) {
        this.jdbc = jdbc;
        String table = props.data().versionTable();
        if (!table.matches("[a-z_]+\\.[a-z_]+")) {
            throw new IllegalArgumentException("bad burnline.data.version-table: " + table);
        }
        this.sql = "select version from " + table + " where country = ?";
    }

    @Override
    public Optional<String> find(Country country) {
        return jdbc.queryForList(sql, String.class, country.name()).stream().findFirst();
    }
}
