package com.burnline.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 프론트가 다른 주소에서 직접 부를 때만 필요하다.
 * Vercel rewrite(/api/* → 터널)로 붙이면 같은 출처라 CORS가 필요 없다.
 * burnline.cors.allowed-origins: 쉼표로 구분 (예: https://burnline.vercel.app,http://localhost:*)
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] origins;

    public CorsConfig(@Value("${burnline.cors.allowed-origins:http://localhost:*}") String origins) {
        this.origins = origins.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("Content-Type", "If-None-Match", "X-Request-ID")
                .exposedHeaders("ETag", "X-Request-ID");
    }
}
