package com.burnline.spatial;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Docker 없이 실제 PostgreSQL을 띄운다 (테스트 JVM 하나에 하나). */
final class TestDb {

    private static EmbeddedPostgres pg;

    private TestDb() {
    }

    static synchronized void register(DynamicPropertyRegistry r) {
        if (pg == null) {
            try {
                pg = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        r.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
    }
}
