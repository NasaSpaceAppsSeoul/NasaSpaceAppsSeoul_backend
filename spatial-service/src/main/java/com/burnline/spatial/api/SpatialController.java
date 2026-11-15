package com.burnline.spatial.api;

import com.burnline.common.cache.JsonCache;
import com.burnline.common.config.BurnlineProperties;
import com.burnline.common.domain.Country;
import com.burnline.common.web.CachedResponses;
import com.burnline.common.web.Params;
import com.burnline.spatial.service.SpatialService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.util.function.Supplier;

/** /api/v1/{country}/(map|events|official-fires). 응답은 data_version을 키로 캐시하고 ETag를 붙인다. */
@RestController
@RequestMapping("/api/v1/{country}")
@RequiredArgsConstructor
public class SpatialController {

    private final SpatialService service;
    private final JsonCache cache;
    private final CachedResponses responses;
    private final BurnlineProperties props;

    /** 명세 7-6: 구역별 값·map_bin + 감지 점 (4,000개 넘으면 표본, count·shown) */
    @GetMapping("/map")
    public ResponseEntity<JsonNode> map(@PathVariable Country country, @RequestParam String month,
                                        @RequestParam(required = false) String parent, WebRequest request) {
        Params.month(month, "month");
        return cached(request, country, "map:" + month + ":" + parent, service.recent(country, month), () -> service.map(country, month, parent));
    }

    /** 명세 7-9: 감지 묶음 (NOAA-20, 2018-04~) */
    @GetMapping("/events")
    public ResponseEntity<JsonNode> events(@PathVariable Country country,
                                           @RequestParam(required = false) String region,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to, WebRequest request) {
        Params.monthRange(from, to);   // 형식 + 순서 (from > to면 INVALID_PARAM, A /series와 같은 규칙)
        return cached(request, country, "events:" + region + ":" + from + ":" + to, service.recent(country, to), () -> service.events(country, region, from, to));
    }

    /** 명세 7-13: 공식 산불 기록 (나라별 출처) */
    @GetMapping("/official-fires")
    public ResponseEntity<JsonNode> officialFires(@PathVariable Country country,
                                                  @RequestParam(required = false) String region,
                                                  @RequestParam(required = false) String month, WebRequest request) {
        Params.month(month, "month");
        return cached(request, country, "fires:" + region + ":" + month, service.recent(country, month), () -> service.officialFires(country, region, month));
    }

    /** 서버 캐시는 키에 data_version이 있어 다시 적재하면 바뀐다. 최근 달(partial·nrt)은 브라우저 캐시만 짧게 */
    private ResponseEntity<JsonNode> cached(WebRequest request, Country country, String key, boolean recent, Supplier<?> loader) {
        String version = service.version(country);
        JsonNode body = cache.getOrLoadTree(country + ":spatial:" + key + ":" + version, props.cache().ttlFinal(), loader);
        return responses.ok(request, body, version, recent);
    }
}
