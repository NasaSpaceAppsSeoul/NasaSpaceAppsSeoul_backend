package com.burnline.insight.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** 테스트에서 패키지 전용 계산 함수를 부르기 위한 통로 */
public final class InsightServiceTestAccess {

    private InsightServiceTestAccess() {
    }

    public static List<Map<String, Object>> strip(JsonNode series, int m) {
        return InsightService.strip(series, m);
    }

    public static List<Map<String, Object>> topYears(List<Map<String, Object>> strip, int year) {
        return InsightService.topYears(strip, year);
    }

    public static List<Double> monthly(JsonNode series, String from) {
        return InsightService.monthly(series, from);
    }

    public static JsonNode longterm(JsonNode lt) {
        return InsightService.longterm(lt);
    }

    public static JsonNode seriesMonth(JsonNode series, String month) {
        return InsightService.seriesMonth(series, month);
    }

    public static int binOrder(String bin) {
        return LandingService.binOrder(bin);
    }
}
