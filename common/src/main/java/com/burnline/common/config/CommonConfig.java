package com.burnline.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 각 서비스는 @SpringBootApplication(scanBasePackages = "com.burnline")로 이 패키지를 함께 스캔한다.
 * 공통 설정 파일은 서비스 application.yml에서 spring.config.import: classpath:burnline-common.yml
 */
@Configuration
@EnableConfigurationProperties(BurnlineProperties.class)
public class CommonConfig {
}
