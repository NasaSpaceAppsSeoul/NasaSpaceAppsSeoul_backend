package com.burnline.spatial;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * spatial 서비스 (담당 B): 감지 점 · 감지 묶음 · 공식 산불 기록.
 * 공개: /map, /events, /official-fires
 * 내부: /internal/month-detail, /internal/top, /internal/landing (insight가 부름)
 */
@SpringBootApplication(scanBasePackages = "com.burnline")
public class SpatialApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpatialApplication.class, args);
    }
}
