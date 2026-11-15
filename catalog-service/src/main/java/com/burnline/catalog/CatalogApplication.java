package com.burnline.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * catalog 서비스 (담당 A): 구역·지역 정보·메타데이터.
 * 공개: /meta, /regions, /regions/{id}, /geo
 * 내부: /internal/regions/{country}/{regionId} (timeseries·insight가 부름)
 */
@SpringBootApplication(scanBasePackages = "com.burnline")
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
