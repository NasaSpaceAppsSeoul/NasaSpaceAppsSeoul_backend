package com.burnline.common.version;

import com.burnline.common.domain.Country;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 명세 data_version (= meta.json의 generated). ETag와 캐시 키에 쓴다.
 * 워커가 적재를 마치면 ops.data_version과 Redis 키 data_version:{country}를 함께 갱신한다.
 */
@Slf4j
@Service
public class DataVersionService {

    public static final String UNKNOWN = "unknown";

    private final StringRedisTemplate redis;
    private final ObjectProvider<DataVersionSource> db;

    public DataVersionService(StringRedisTemplate redis, ObjectProvider<DataVersionSource> db) {
        this.redis = redis;
        this.db = db;
    }

    public static String key(Country country) {
        return "data_version:" + country.name();
    }

    public String current(Country country) {
        try {
            String v = redis.opsForValue().get(key(country));
            if (v != null) {
                return v;
            }
        } catch (Exception e) {
            log.warn("data_version from redis failed: {}", e.toString());
        }
        DataVersionSource source = db.getIfAvailable();
        if (source == null) {
            return UNKNOWN;
        }
        return source.find(country).map(v -> {
            try {
                redis.opsForValue().set(key(country), v);
            } catch (Exception ignored) {
                // 캐시 장애는 무시
            }
            return v;
        }).orElse(UNKNOWN);
    }
}
