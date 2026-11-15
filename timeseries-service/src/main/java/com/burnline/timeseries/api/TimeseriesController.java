package com.burnline.timeseries.api;

import com.burnline.common.cache.JsonCache;
import com.burnline.common.config.BurnlineProperties;
import com.burnline.common.domain.Country;
import com.burnline.common.version.DataVersionService;
import com.burnline.common.web.CachedResponses;
import com.burnline.timeseries.judge.JudgeService;
import com.burnline.timeseries.service.SeriesService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.nio.charset.StandardCharsets;

/** /api/v1/{country}/(series|baseline|judgement|export.csv) */
@RestController
@RequestMapping("/api/v1/{country}")
@RequiredArgsConstructor
public class TimeseriesController {

    private final SeriesService series;
    private final JudgeService judge;
    private final JsonCache cache;
    private final DataVersionService versions;
    private final CachedResponses responses;
    private final BurnlineProperties props;

    /** 명세 7-3 */
    @GetMapping("/series/{regionId}")
    public ResponseEntity<JsonNode> series(@PathVariable Country country, @PathVariable String regionId,
                                           @RequestParam(name = "from", required = false) String from,
                                           @RequestParam(required = false) String to, WebRequest request) {
        String version = versions.current(country);
        // 서버 캐시는 키에 data_version이 있어 다시 적재하면 바뀐다. 진행 중인 달은 브라우저 캐시만 짧게.
        JsonNode body = cache.getOrLoadTree("%s:series:%s:%s:%s:%s".formatted(country, regionId, from, to, version),
                props.cache().ttlFinal(), () -> series.series(country, regionId, from, to).body());
        boolean recent = body.path("months").findValuesAsText("status").stream().anyMatch(s -> !"final".equals(s));
        return responses.ok(request, body, version, recent);
    }

    /** 명세 7-4 */
    @GetMapping("/baseline/{regionId}")
    public ResponseEntity<JsonNode> baseline(@PathVariable Country country, @PathVariable String regionId,
                                             WebRequest request) {
        String version = versions.current(country);
        JsonNode body = cache.getOrLoadTree("%s:baseline:%s:%s".formatted(country, regionId, version),
                props.cache().ttlFinal(), () -> series.baseline(country, regionId));
        return responses.ok(request, body, version);
    }

    /**
     * 지도용 일괄 판정: regionId 없이 country+month만으로 전체 dist 단위의 chip·ratio_to_median 반환.
     * 한국 229개 지역을 1번 호출로 해결 (명세 추가).
     */
    @GetMapping("/judgement")
    public ResponseEntity<JsonNode> judgementBulk(@PathVariable Country country,
                                                  @RequestParam String month, WebRequest request) {
        String version = versions.current(country);
        JsonNode body = cache.getOrLoadTree("%s:judge-bulk:%s:%s".formatted(country, month, version),
                props.cache().ttlFinal(), () -> judge.bulkMap(country, month));
        return responses.ok(request, body, version);
    }

    /** 명세 7-5 */
    @GetMapping("/judgement/{regionId}")
    public ResponseEntity<JsonNode> judgement(@PathVariable Country country, @PathVariable String regionId,
                                              @RequestParam String month, WebRequest request) {
        String version = versions.current(country);
        JsonNode body = cache.getOrLoadTree("%s:judge:%s:%s:%s".formatted(country, regionId, month, version),
                props.cache().ttlFinal(), () -> judge.judge(country, regionId, month));
        return responses.ok(request, body, version, !"final".equals(body.path("status").asText()));
    }

    /**
     * 명세 7-11. 캐싱은 1장 규칙대로 ETag = data_version.
     * CSV는 항상 최근 달(partial·nrt)까지 들어 있으므로 짧은 캐시로 준다.
     */
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> exportCsv(@PathVariable Country country, @RequestParam String id,
                                            WebRequest request) {
        String version = versions.current(country);
        byte[] csv = series.csv(country, id);   // 없는 지역은 여기서 404 (304보다 먼저)
        ResponseEntity<byte[]> cached = responses.ok(request, csv, version, true);
        if (!cached.getStatusCode().is2xxSuccessful()) {
            return cached;   // 304 Not Modified
        }
        return ResponseEntity.ok()
                .headers(cached.getHeaders())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("burnline_%s_%s.csv".formatted(country, id), StandardCharsets.UTF_8).build().toString())
                .body(csv);
    }
}
