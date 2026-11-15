package com.burnline.common.client;

import com.burnline.common.web.RequestIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.slf4j.MDC;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 서비스 → 서비스 호출. InternalClientFactory로 만든다.
 * <ul>
 *   <li>재시도: 연결 실패·타임아웃·5xx(501 제외)만, 지수 백오프</li>
 *   <li>서킷 브레이커: 실패율이 넘으면 열고 openDuration 동안 바로 실패 (장애 전파 차단)</li>
 *   <li>X-Request-ID를 그대로 넘겨 로그를 이어 본다</li>
 * </ul>
 */
public class InternalClient {

    private final String target;
    private final RestClient rest;
    private final Retry retry;
    private final CircuitBreaker breaker;
    private final ObjectMapper mapper;

    InternalClient(String target, RestClient rest, Retry retry, CircuitBreaker breaker, ObjectMapper mapper) {
        this.target = target;
        this.rest = rest;
        this.retry = retry;
        this.breaker = breaker;
        this.mapper = mapper;
    }

    public String target() {
        return target;
    }

    /**
     * @param path  "/internal/regions/{country}/{id}" 같은 템플릿
     * @param query 쿼리 파라미터 (null 값은 빠짐)
     * @param vars  경로 변수 (한글 ID도 인코딩됨)
     */
    public JsonNode get(String path, Map<String, ?> query, Object... vars) {
        Supplier<JsonNode> call = () -> rest.get()
                .uri(b -> {
                    b.path(path);
                    if (query != null) {
                        query.forEach((k, v) -> {
                            if (v != null) {
                                b.queryParam(k, v);
                            }
                        });
                    }
                    return b.build(vars);
                })
                .headers(h -> {
                    String id = MDC.get(RequestIdFilter.MDC_KEY);
                    if (id != null) {
                        h.set(RequestIdFilter.HEADER, id);
                    }
                })
                .retrieve()
                .body(JsonNode.class);

        Supplier<JsonNode> guarded = CircuitBreaker.decorateSupplier(breaker, Retry.decorateSupplier(retry, call));
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            throw new UpstreamException(target, target + ": circuit open", null, null, e);
        } catch (HttpStatusCodeException e) {
            JsonNode body = parse(e.getResponseBodyAsString());
            String code = body.path("code").asText(null);
            String message = body.path("message").asText(e.getMessage());
            throw new UpstreamException(target, message, e.getStatusCode().value(), code, e);
        } catch (ResourceAccessException e) {
            throw new UpstreamException(target, target + ": " + e.getMessage(), null, null, e);
        }
    }

    public JsonNode get(String path, Object... vars) {
        return get(path, null, vars);
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    /** 재시도·서킷 실패로 칠 예외: 연결 문제와 5xx(501 NOT_IMPLEMENTED 제외) */
    static boolean isFailure(Throwable t) {
        if (t instanceof ResourceAccessException) {
            return true;
        }
        return t instanceof HttpStatusCodeException h && h.getStatusCode().is5xxServerError()
                && h.getStatusCode().value() != 501;
    }
}
