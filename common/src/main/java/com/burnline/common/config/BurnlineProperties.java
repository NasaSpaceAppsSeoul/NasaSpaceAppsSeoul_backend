package com.burnline.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** burnline.* 설정 (기본값은 common의 burnline-common.yml). */
@ConfigurationProperties("burnline")
public record BurnlineProperties(@DefaultValue Cache cache,
                                 @DefaultValue Internal internal,
                                 @DefaultValue Services services,
                                 @DefaultValue Data data) {

    public record Cache(@DefaultValue("24h") Duration ttlFinal,
                        @DefaultValue("5m") Duration ttlRecent) {
    }

    /** 내부 호출: 재시도 + 서킷 브레이커 */
    public record Internal(@DefaultValue("3s") Duration timeout,
                           @DefaultValue("3") int retries,
                           @DefaultValue("200ms") Duration backoff,
                           @DefaultValue("50") float failureRatePercent,
                           @DefaultValue("30s") Duration openDuration) {
    }

    /** 다른 서비스 주소 */
    public record Services(@DefaultValue("http://localhost:8081") String catalog,
                           @DefaultValue("http://localhost:8082") String timeseries,
                           @DefaultValue("http://localhost:8083") String spatial) {
    }

    /**
     * 원천 데이터와 적재.
     * dir: burnline_backend_data/burnline_backend_data (output/, boundaries/ 가 들어 있는 폴더)
     * load: never | if-empty(기본: 비어 있는 나라만 시작할 때 적재) | always
     */
    public record Data(@DefaultValue("../../burnline_backend_data/burnline_backend_data") String dir,
                       @DefaultValue("if-empty") String load,
                       @DefaultValue("ops.data_version") String versionTable) {
    }
}
