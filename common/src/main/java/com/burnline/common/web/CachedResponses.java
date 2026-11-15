package com.burnline.common.web;

import com.burnline.common.config.BurnlineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

/**
 * 명세 1장 캐싱 규칙: ETag = data_version.
 * 과거 달은 data_version이 바뀔 때까지 길게, 최근 달(partial·nrt)이 들어간 응답은 짧게.
 */
@Component
@RequiredArgsConstructor
public class CachedResponses {

    private final BurnlineProperties props;

    public <T> ResponseEntity<T> ok(WebRequest request, T body, String version, boolean recent) {
        String etag = "W/\"" + version + "\"";
        var ttl = recent ? props.cache().ttlRecent() : props.cache().ttlFinal();
        var cache = CacheControl.maxAge(ttl).cachePublic();
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(cache).body(body);
    }

    public <T> ResponseEntity<T> ok(WebRequest request, T body, String version) {
        return ok(request, body, version, false);
    }
}
