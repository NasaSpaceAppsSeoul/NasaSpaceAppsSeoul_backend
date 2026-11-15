package com.burnline.insight.api;

import com.burnline.common.client.InternalClient;
import com.burnline.common.domain.Country;
import com.burnline.common.web.CachedResponses;
import com.burnline.common.web.Params;
import com.burnline.insight.service.InsightService;
import com.burnline.insight.service.LandingService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * /api/v1/{country}/(insight|top|landing). DB가 없어 data_version은 spatial 응답에서 받아 ETag로 쓴다.
 */
@RestController
@RequestMapping("/api/v1/{country}")
public class InsightController {

    private final InsightService insight;
    private final LandingService landing;
    private final InternalClient spatial;
    private final CachedResponses responses;

    public InsightController(InsightService insight, LandingService landing,
                             @Qualifier("spatialClient") InternalClient spatial, CachedResponses responses) {
        this.insight = insight;
        this.landing = landing;
        this.spatial = spatial;
        this.responses = responses;
    }

    /** 명세 4장 · 7-8: 카드 1~9 */
    @GetMapping("/insight/{regionId}")
    public ResponseEntity<Map<String, Object>> insight(@PathVariable Country country, @PathVariable String regionId,
                                                       @RequestParam String month, WebRequest request) {
        var r = insight.insight(country, regionId, Params.month(month, "month"));
        return responses.ok(request, r.body(), String.valueOf(r.body().get("data_version")), r.recent());
    }

    /** 명세 7-7: 나라·시·도 = 순위 → H 순 3곳, 시·군·구(parent = 그 ID) = 맞닿은 지역 H 순 */
    @GetMapping("/top")
    public ResponseEntity<JsonNode> top(@PathVariable Country country, @RequestParam String month,
                                        @RequestParam(required = false) String parent, WebRequest request) {
        Map<String, Object> q = new HashMap<>();
        q.put("month", Params.month(month, "month"));
        q.put("parent", parent);
        JsonNode body = spatial.get("/internal/top/{c}", q, country.name());
        boolean recent = body.path("recent").asBoolean(true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove("recent");   // 내부용 표시는 밖으로 내보내지 않는다
        return responses.ok(request, body, body.path("data_version").asText("unknown"), recent);
    }

    /** 명세 7-10 */
    @GetMapping("/landing")
    public ResponseEntity<Map<String, Object>> landing(@PathVariable Country country, WebRequest request) {
        var r = landing.landing(country);
        return responses.ok(request, r.body(), String.valueOf(r.body().get("data_version")), r.recent());
    }
}
