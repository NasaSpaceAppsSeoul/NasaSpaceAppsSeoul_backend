package com.burnline.common.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Redis 조회 결과 캐시. 키에 data_version을 넣으므로 다시 적재하면 옛 캐시는 자연히 안 쓰인다.
 * Redis가 죽어도 요청은 실패하지 않고 그냥 DB로 간다.
 *
 * <pre>
 * cache.getOrLoad("meta:" + country + ":" + version, ttl, MetaResponse.class, () -> repo.meta(country));
 * </pre>
 */
@Slf4j
@Component
public class JsonCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final String prefix;

    public JsonCache(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry meters,
                     @Value("${spring.application.name}") String service) {
        this.redis = redis;
        this.mapper = mapper;
        this.meters = meters;
        this.prefix = service + ":";
    }

    public <T> T getOrLoad(String key, Duration ttl, Class<T> type, Supplier<T> loader) {
        String full = prefix + key;
        try {
            String raw = redis.opsForValue().get(full);
            if (raw != null) {
                count("hit");
                return mapper.readValue(raw, type);
            }
        } catch (Exception e) {
            log.warn("cache get failed key={} error={}", full, e.toString());
        }
        count("miss");
        T value = loader.get();
        if (value != null) {
            try {
                redis.opsForValue().set(full, mapper.writeValueAsString(value), ttl);
            } catch (Exception e) {
                log.warn("cache set failed key={} error={}", full, e.toString());
            }
        }
        return value;
    }

    /** 응답 객체(record·Map 등)를 JSON 트리로 바꿔 캐시. 없으면 null. */
    public JsonNode getOrLoadTree(String key, Duration ttl, Supplier<?> loader) {
        return getOrLoad(key, ttl, JsonNode.class, () -> {
            Object value = loader.get();
            return value == null ? null : mapper.valueToTree(value);
        });
    }

    private void count(String result) {
        meters.counter("burnline.cache.lookups", "result", result).increment();
    }
}
