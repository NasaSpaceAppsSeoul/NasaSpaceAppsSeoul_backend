package com.burnline.catalog.api;

import com.burnline.catalog.service.CatalogService;
import com.burnline.common.cache.JsonCache;
import com.burnline.common.config.BurnlineProperties;
import com.burnline.common.domain.Codes.Level;
import com.burnline.common.domain.Country;
import com.burnline.common.version.DataVersionService;
import com.burnline.common.web.CachedResponses;
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

/** /api/v1/{country}/(meta|regions|geo). 응답은 data_version을 키로 캐시하고 ETag를 붙인다. */
@RestController
@RequestMapping("/api/v1/{country}")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService service;
    private final JsonCache cache;
    private final DataVersionService versions;
    private final CachedResponses responses;
    private final BurnlineProperties props;

    @GetMapping("/meta")
    public ResponseEntity<JsonNode> meta(@PathVariable Country country, WebRequest request) {
        return cached(request, country, "meta", () -> service.meta(country));
    }

    @GetMapping("/regions")
    public ResponseEntity<JsonNode> regions(@PathVariable Country country,
                                            @RequestParam(required = false) Level level,
                                            @RequestParam(required = false) String q, WebRequest request) {
        String lv = level == null ? null : level.name();
        return cached(request, country, "regions:" + lv + ":" + q, () -> service.regions(country, lv, q));
    }

    @GetMapping("/regions/{regionId}")
    public ResponseEntity<JsonNode> region(@PathVariable Country country, @PathVariable String regionId,
                                           WebRequest request) {
        return cached(request, country, "region:" + regionId, () -> service.region(country, regionId));
    }

    @GetMapping("/geo")
    public ResponseEntity<JsonNode> geo(@PathVariable Country country,
                                        @RequestParam(required = false) Level level,
                                        @RequestParam(required = false) String parent, WebRequest request) {
        String lv = level == null ? null : level.name();
        return cached(request, country, "geo:" + lv + ":" + parent, () -> service.geo(country, lv, parent));
    }

    private ResponseEntity<JsonNode> cached(WebRequest request, Country country, String key, Supplier<?> loader) {
        String version = versions.current(country);
        JsonNode body = cache.getOrLoadTree(country + ":" + key + ":" + version, props.cache().ttlFinal(), loader);
        return responses.ok(request, body, version);
    }
}
