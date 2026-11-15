package com.burnline.insight.config;

import com.burnline.common.client.InternalClient;
import com.burnline.common.client.InternalClientFactory;
import com.burnline.common.config.BurnlineProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 다른 서비스 호출 (재시도·서킷 브레이커는 common의 InternalClient) */
@Configuration
public class Clients {

    @Bean
    InternalClient catalogClient(InternalClientFactory f, BurnlineProperties p) {
        return f.create("catalog", p.services().catalog());
    }

    @Bean
    InternalClient timeseriesClient(InternalClientFactory f, BurnlineProperties p) {
        return f.create("timeseries", p.services().timeseries());
    }

    @Bean
    InternalClient spatialClient(InternalClientFactory f, BurnlineProperties p) {
        return f.create("spatial", p.services().spatial());
    }
}
