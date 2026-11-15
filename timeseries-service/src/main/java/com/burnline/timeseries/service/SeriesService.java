package com.burnline.timeseries.service;

import com.burnline.common.domain.Country;
import com.burnline.common.error.ApiException;
import com.burnline.common.web.Params;
import com.burnline.timeseries.repo.TimeseriesRepository;
import com.burnline.timeseries.repo.TimeseriesRepository.BaselineRow;
import com.burnline.timeseries.repo.TimeseriesRepository.RegionInfo;
import com.burnline.timeseries.repo.TimeseriesRepository.SeriesRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 명세 7-3 /series, 7-4 /baseline, 7-11 /export.csv */
@Service
@RequiredArgsConstructor
public class SeriesService {

    private final TimeseriesRepository repo;
    private final Months months;

    /** 응답과, 진행 중인 달(partial·nrt)이 들어 있는지 (짧게 캐시할지) */
    public record Result(Map<String, Object> body, boolean recent) {
    }

    public Result series(Country country, String id, String from, String to) {
        RegionInfo r = region(country, id);
        Params.monthRange(from, to);   // 형식 + 뒤집힌 범위 (어떤 달이든 from > to면 400 INVALID_PARAM)
        months.check(country, from, "from");
        months.check(country, to, "to");
        List<Map<String, Object>> items = repo.series(country, id, from, to).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", s.month());
            m.put("value", s.value());
            m.put("raw_before", s.rawBefore());
            m.put("source", s.source());
            m.put("quality", s.quality());
            m.put("estimated", s.estimated());
            m.put("status", s.status());
            m.put("rank", s.value() != null && s.value() > 0 ? s.rank() : null);
            m.put("n", s.n());
            m.put("ratio_to_median", r.selfJudged() ? s.ratioToMedian() : null);
            return m;
        }).toList();
        boolean recent = items.stream().anyMatch(m -> !"final".equals(m.get("status")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", r.id());
        body.put("level", r.level());
        body.put("months", items);
        return new Result(body, recent);
    }

    /** 드문 지역은 band를 null로 (화면에서 띠를 그리지 않음, 명세 7-8) */
    public Map<String, Object> baseline(Country country, String id) {
        RegionInfo r = region(country, id);
        List<Map<String, Object>> byMonth = repo.baseline(country, id).stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", b.m());
            m.put("median", b.median());
            m.put("band_low", r.selfJudged() ? b.bandLow() : null);
            m.put("band_high", r.selfJudged() ? b.bandHigh() : null);
            return m;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", r.id());
        body.put("period", List.of(r.periodStart(), r.periodEnd()));
        body.put("by_month", byMonth);
        body.put("heat_bins", r.heatBins());
        return body;
    }

    /**
     * 명세 7-11: region,year,month,fire_cells,median,band_low,band_high,rank,years_compared
     * 엑셀에서 한글이 깨지지 않게 UTF-8 BOM을 붙인다.
     */
    public byte[] csv(Country country, String id) {
        RegionInfo r = region(country, id);
        Map<Integer, BaselineRow> base = new HashMap<>();
        repo.baseline(country, id).forEach(b -> base.put(b.m(), b));
        String label = "nat".equals(r.level()) ? r.labelKo() + " 전체" : r.labelKo();

        StringBuilder sb = new StringBuilder("﻿region,year,month,fire_cells,median,band_low,band_high,rank,years_compared\n");
        for (SeriesRow s : repo.series(country, id, null, null)) {
            if (s.value() == null) {
                continue;
            }
            BaselineRow b = base.get(Integer.parseInt(s.month().substring(5)));
            sb.append(csvText(label)).append(',')
                    .append(s.month(), 0, 4).append(',').append(s.month(), 5, 7).append(',')
                    .append(num(s.value())).append(',')
                    .append(b == null ? "" : num(b.median())).append(',')
                    .append(b == null || !r.selfJudged() ? "" : num(b.bandLow())).append(',')
                    .append(b == null || !r.selfJudged() ? "" : num(b.bandHigh())).append(',')
                    .append(s.value() > 0 && s.rank() != null ? s.rank() : "").append(',')
                    .append(s.n() == null ? "" : s.n()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private RegionInfo region(Country country, String id) {
        return repo.region(country, id).orElseThrow(() -> ApiException.regionNotFound(country.name(), id));
    }

    /** 1093.0 → 1093, 95.6 → 95.6 */
    private static String num(Double v) {
        return v == null ? "" : BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static String csvText(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
