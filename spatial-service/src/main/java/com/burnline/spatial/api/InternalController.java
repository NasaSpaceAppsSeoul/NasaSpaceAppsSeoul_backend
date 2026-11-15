package com.burnline.spatial.api;

import com.burnline.common.domain.Country;
import com.burnline.common.web.Params;
import com.burnline.spatial.service.SpatialService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 내부 API (B의 insight가 부른다). 밖으로 열지 않는다. */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final SpatialService service;

    /** 인사이트 카드 2·3·5·7의 사실값: 일별 칸, 가장 넓게 탄 날, 묶음, 50 km, 감지 점, 재발 격자, 공식 기록 */
    @GetMapping("/month-detail/{country}/{regionId}")
    public Map<String, Object> monthDetail(@PathVariable Country country, @PathVariable String regionId,
                                           @RequestParam String month) {
        return service.monthDetail(country, regionId, Params.month(month, "month"));
    }

    /** 목록 카드 TOP3 (나라·시·도) / 맞닿은 지역 (시·군·구) */
    @GetMapping("/top/{country}")
    public Map<String, Object> top(@PathVariable Country country, @RequestParam String month,
                                   @RequestParam(required = false) String parent) {
        return service.top(country, Params.month(month, "month"), parent);
    }

    /** 랜딩 원자료: 보정 전 연간 값, 공식 피해면적 연 합계, 단절 검정, 감지율 */
    @GetMapping("/landing/{country}")
    public JsonNode landing(@PathVariable Country country) {
        return service.landing(country);
    }
}
