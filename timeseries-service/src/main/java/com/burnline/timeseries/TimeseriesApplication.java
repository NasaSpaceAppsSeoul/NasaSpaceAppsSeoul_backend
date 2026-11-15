package com.burnline.timeseries;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * timeseries 서비스 (담당 A): 산불 발생 시계열 통계와 판정.
 * 공개: /series/{id}, /baseline/{id}, /judgement/{id}, /export.csv
 * 내부: /internal/judge (B의 insight가 부름. 판정 로직은 JudgeService 하나뿐)
 */
@SpringBootApplication(scanBasePackages = "com.burnline")
public class TimeseriesApplication {

    public static void main(String[] args) {
        SpringApplication.run(TimeseriesApplication.class, args);
    }
}
