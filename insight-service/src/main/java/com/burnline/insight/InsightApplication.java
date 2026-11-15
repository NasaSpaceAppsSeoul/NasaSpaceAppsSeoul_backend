package com.burnline.insight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * insight 서비스 (담당 B): 화면 조합, DB 없음.
 * 공개: /insight/{id}, /top, /landing
 * 부르는 곳: catalog(구역·이웃) · timeseries(판정·시계열) · spatial(달 상세·순위·랜딩 원자료)
 */
@SpringBootApplication(scanBasePackages = "com.burnline")
public class InsightApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightApplication.class, args);
    }
}
