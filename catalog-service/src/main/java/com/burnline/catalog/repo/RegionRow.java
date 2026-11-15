package com.burnline.catalog.repo;

import com.fasterxml.jackson.databind.JsonNode;

/** catalog.regions 한 행 (jsonb 칸은 JsonNode) */
public record RegionRow(
        String id,
        String level,
        String labelKo,
        String nameEn,
        String province,
        String parentId,
        String regionType,
        String judgeUnit,
        String refProvince,
        String basis,
        JsonNode riskMonths,
        Double riskShare,
        String riskFrom,
        Integer peakMonth,
        JsonNode center,
        JsonNode bbox,
        JsonNode neighbors,
        JsonNode geometry) {
}
