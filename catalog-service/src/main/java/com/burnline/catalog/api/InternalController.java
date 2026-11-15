package com.burnline.catalog.api;

import com.burnline.catalog.service.CatalogService;
import com.burnline.common.domain.Country;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 내부 API (B의 insight가 이웃·머리 카드에 쓴다). 밖으로 열지 않는다. */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final CatalogService service;

    /** 명세 7-2와 같은 모양. 없으면 404 REGION_NOT_FOUND. */
    @GetMapping("/regions/{country}/{regionId}")
    public Map<String, Object> region(@PathVariable Country country, @PathVariable String regionId) {
        return service.region(country, regionId);
    }
}
