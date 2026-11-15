package com.burnline.common.client;

import com.burnline.common.config.BurnlineProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * 서비스에서 이렇게 쓴다:
 * <pre>
 * &#64;Bean InternalClient catalogClient(InternalClientFactory f, BurnlineProperties p) {
 *     return f.create("catalog", p.services().catalog());
 * }
 * </pre>
 * 메트릭: resilience4j_circuitbreaker_state, resilience4j_retry_calls 등이 /metrics에 나온다.
 */
@Component
public class InternalClientFactory {

    private final RestClient.Builder builder;
    private final ObjectMapper mapper;
    private final BurnlineProperties.Internal props;
    private final RetryRegistry retries;
    private final CircuitBreakerRegistry breakers;

    public InternalClientFactory(RestClient.Builder builder, ObjectMapper mapper, BurnlineProperties props,
                                 MeterRegistry meters) {
        this.builder = builder;
        this.mapper = mapper;
        this.props = props.internal();

        this.retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(Math.max(1, this.props.retries()))
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(this.props.backoff(), 2.0))
                .retryOnException(InternalClient::isFailure)
                .build());
        this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(this.props.failureRatePercent())
                .waitDurationInOpenState(this.props.openDuration())
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(InternalClient::isFailure)
                .build());
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meters);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters);
    }

    public InternalClient create(String target, String baseUrl) {
        var http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.timeout());
        RestClient rest = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        return new InternalClient(target, rest, retries.retry(target), breakers.circuitBreaker(target), mapper);
    }
}
