package com.burnline.timeseries.judge;

import com.burnline.common.domain.Codes.Chip;
import com.burnline.common.domain.Codes.Reason;
import com.burnline.common.domain.Country;
import com.burnline.common.domain.Rules;
import com.burnline.common.error.ApiException;
import com.burnline.timeseries.repo.TimeseriesRepository;
import com.burnline.timeseries.repo.TimeseriesRepository.BaselineRow;
import com.burnline.timeseries.repo.TimeseriesRepository.MapJudgeRow;
import com.burnline.timeseries.repo.TimeseriesRepository.RegionInfo;
import com.burnline.timeseries.repo.TimeseriesRepository.SeriesRow;
import com.burnline.timeseries.service.Months;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 판정. 역할 분담 문서의 공통 인터페이스: judge(country, regionId, month) → 명세 7-5 JSON.
 * 판정 로직은 이 클래스 하나에만 둔다 (/judgement, /internal/judge 모두 여기로).
 *
 * <h3>규칙 (방법론 v3.1, 명세 2·5장)</h3>
 * <ul>
 *   <li>칩, 위에서부터 먼저 맞는 하나: record(같은 달 1위이고 0보다 큼) → above(범위 위) → below(범위 아래) → normal</li>
 *   <li>범위 = 15~85 퍼센타일 + 2√평년 (파이프라인이 band_low·band_high로 계산해 둠)</li>
 *   <li>자체 판정은 region_type = sufficient일 때만. 아니면 chip·rank·ratio·범위(band)는 null이고 (평년 median은 준다)
 *       ref에 빌려 쓴 지역(시·도 → 가까운 충분 시·도, CAL·BC는 주 전체)의 판정을 붙인다</li>
 *   <li>reason (먼저 맞는 하나): PRE_2012_DISTRICT → NO_RECORD → IN_PROGRESS → NO_DETECTION → ZERO_BASELINE</li>
 *   <li>NO_DETECTION: value 0, rank·ratio null (칩은 계산). ZERO_BASELINE: ratio null</li>
 *   <li>올해 누적: 시즌 시작 달(북반구 1월, 남반구 CHL·NSW 7월, Country.seasonStart)부터 그달까지.
 *       기준 기간 해마다 같은 기간 누적 합을 구해 ytd_median = 중앙값, ytd_lo·ytd_hi = 월 범위와 같은 규칙,
 *       ytd_ratio = ytd_sum ÷ ytd_median (repo.ytdBand). 달마다 평년을 더하면 누적 합의 중앙값보다
 *       3~30% 작아 비율이 부풀므로 쓰지 않는다 (누적 합이 3개 미만일 때만 대신 씀)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class JudgeService {

    private final TimeseriesRepository repo;
    private final Months months;

    public Map<String, Object> judge(Country country, String regionId, String month) {
        RegionInfo region = repo.region(country, regionId)
                .orElseThrow(() -> ApiException.regionNotFound(country.name(), regionId));
        months.check(country, month);
        Judgement j = core(country, region, month);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", region.id());
        out.put("label_ko", region.labelKo());
        out.put("month", month);
        out.put("value", j.value);
        out.put("region_type", region.regionType());
        out.put("chip", j.chip);
        out.put("judge_unit", region.judgeUnit());
        Map<String, Object> ref = region.refId() == null ? null : ref(country, region.refId(), month);
        // 명세 3장 #7: ref_id·ref_chip (평평한 필드). 7-5 예시의 ref 객체도 그대로 준다
        out.put("ref_id", ref == null ? null : ref.get("id"));
        out.put("ref_chip", ref == null ? null : ref.get("chip"));
        out.put("rank", j.rank);
        out.put("n", j.n);
        out.put("rank_period", j.self ? List.of(region.periodStart(), region.periodEnd()) : null);
        out.put("median", j.median);
        out.put("band_low", j.bandLow);
        out.put("band_high", j.bandHigh);
        out.put("ratio_to_median", j.ratio);
        Double ytd = j.value == null ? null : repo.ytd(country, regionId, month);
        out.put("ytd_sum", ytd);
        // 누적 평년 = 해마다 같은 기간 누적 합의 중앙값 (범위 ytd_lo·ytd_hi와 같은 기준).
        // 누적 합이 3개 미만이라 계산할 수 없으면 달마다 평년을 더한 값으로 대신한다
        double[] band = j.self && ytd != null
                ? repo.ytdBand(country, regionId, month, region.periodStart(), region.periodEnd()) : null;
        Double ytdNormal = band != null ? Double.valueOf(band[2])
                : j.self && ytd != null ? repo.baselineYtd(country, regionId, month) : null;
        out.put("ytd_median", ytdNormal == null ? null : Math.round(ytdNormal * 10) / 10.0);
        out.put("ytd_ratio", j.self ? ytdRatio(ytd, ytdNormal) : null);
        out.put("ytd_lo", band != null && band[0] > 0 ? band[0] : null);
        out.put("ytd_hi", band != null && band[1] > 0 ? band[1] : null);
        out.put("quality", j.quality);
        out.put("estimated", j.estimated);
        out.put("status", j.status);
        out.put("reason", j.reason == null ? null : j.reason.name());
        out.put("ref", ref);
        return out;
    }

    /** 빌려 쓴 지역의 판정 (명세 7-5 드문 지역 예시의 ref) */
    private Map<String, Object> ref(Country country, String refId, String month) {
        RegionInfo ref = repo.region(country, refId).orElse(null);
        if (ref == null) {
            return null;
        }
        Judgement j = core(country, ref, month);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ref.id());
        m.put("label_ko", ref.labelKo());
        m.put("value", j.value);
        m.put("chip", j.chip);
        m.put("rank", j.rank);
        m.put("n", j.n);
        m.put("ratio_to_median", j.ratio);
        m.put("basis", ref.basis());
        return m;
    }

    Judgement core(Country country, RegionInfo region, String month) {
        SeriesRow s = repo.seriesMonth(country, region.id(), month).orElse(null);
        BaselineRow b = repo.baselineMonth(country, region.id(), Integer.parseInt(month.substring(5))).orElse(null);
        Judgement j = new Judgement();
        j.self = region.selfJudged();
        j.value = s == null ? null : s.value();
        j.quality = s == null ? null : s.quality();
        j.estimated = s != null ? s.estimated() : Rules.estimated(levelOf(region), month);
        j.status = s == null ? "final" : s.status();
        Double median = b == null ? null : b.median();

        if (j.value == null) {
            j.reason = month.compareTo(Rules.VIIRS_START) < 0 ? Reason.PRE_2012_DISTRICT : null;
            j.n = s == null ? null : s.n();
            return j;
        }
        if ("none".equals(region.regionType())) {
            j.reason = Reason.NO_RECORD;
        } else if ("partial".equals(j.status)) {
            j.reason = Reason.IN_PROGRESS;
        } else if (j.value == 0) {
            j.reason = Reason.NO_DETECTION;
        } else if (j.self && median != null && median == 0) {
            // 평년 대비 배수는 자체 판정할 때만 의미가 있다. 드문 지역은 ref가 대신한다 (명세 7-5 의성: reason null)
            j.reason = Reason.ZERO_BASELINE;
        }
        j.n = s.n();
        j.median = median;   // 평년은 드문 지역도 준다 (명세 7-8 의성 median 0.0)
        if (!j.self) {
            return j;   // chip·rank·ratio·범위(band)는 null, ref가 대신한다 (명세 7-5, 7-8)
        }
        j.bandLow = b == null ? null : b.bandLow();
        j.bandHigh = b == null ? null : b.bandHigh();
        j.rank = j.value > 0 ? s.rank() : null;
        j.ratio = j.value > 0 && median != null && median > 0 ? s.ratioToMedian() : null;
        j.chip = chip(j.value, s.rank(), j.bandLow, j.bandHigh);
        return j;
    }

    /**
     * 명세 3장 #7 ytd_ratio: 그해 누적(ytd_sum) ÷ 같은 기간(1월~그달) 평년 median 합. 비율은 두 자리.
     * ratio_to_median과 같이 자체 판정할 때만 주고, 평년 합이 0이면 null.
     */
    static Double ytdRatio(Double ytd, Double baselineYtd) {
        if (ytd == null || baselineYtd == null || baselineYtd <= 0) {
            return null;
        }
        return Math.round(ytd / baselineYtd * 100) / 100.0;
    }

    /** 명세 2장 chip: 위에서부터 먼저 맞는 하나 */
    static Chip chip(double value, Integer rank, Double bandLow, Double bandHigh) {
        if (value > 0 && rank != null && rank == 1) {
            return Chip.record;
        }
        if (bandHigh != null && value > bandHigh) {
            return Chip.above;
        }
        if (bandLow != null && value < bandLow) {
            return Chip.below;
        }
        return Chip.normal;
    }

    /**
     * 지도용 일괄 판정: 나라 전체 dist 단위의 chip·ratio_to_median을 한 번에 (한국 229번 → 1번).
     * 상세 판정(judge)과 같은 규칙: 자체 판정 구역은 chip(값 0이어도), 드문 구역은 chip null + 빌려 쓴 판정 ref_id·ref_chip.
     */
    public Map<String, Object> bulkMap(Country country, String month) {
        months.check(country, month);
        List<MapJudgeRow> rows = repo.mapJudge(country, month);
        Map<String, MapJudgeRow> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.regionId(), r));
        List<Map<String, Object>> items = new ArrayList<>();
        for (MapJudgeRow r : rows) {
            if (!"dist".equals(r.level())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.regionId());
            m.put("chip", selfChip(r));
            m.put("ratio_to_median", selfRatio(r));
            m.put("judge_unit", r.judgeUnit());
            MapJudgeRow ref = r.refId() == null ? null : byId.get(r.refId());
            m.put("ref_id", r.refId());
            m.put("ref_chip", ref == null ? null : selfChip(ref));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("regions", items);
        return out;
    }

    /** core()와 같은 칩 규칙: 자체 판정이고 값이 있을 때만 */
    private static String selfChip(MapJudgeRow r) {
        return r.selfJudged() && r.value() != null ? chip(r.value(), r.rank(), r.bandLow(), r.bandHigh()).name() : null;
    }

    /** core()와 같은 배수 규칙: 값과 평년이 모두 0보다 클 때만 */
    private static Double selfRatio(MapJudgeRow r) {
        return r.selfJudged() && r.value() != null && r.value() > 0 && r.median() != null && r.median() > 0
                ? r.ratioToMedian() : null;
    }

    private static com.burnline.common.domain.Codes.Level levelOf(RegionInfo r) {
        return com.burnline.common.domain.Codes.Level.valueOf(r.level());
    }

    /** 한 지역 한 달의 판정 결과 */
    static final class Judgement {
        boolean self;
        Double value;
        Chip chip;
        Integer rank;
        Integer n;
        Double median;
        Double bandLow;
        Double bandHigh;
        Double ratio;
        String quality;
        boolean estimated;
        String status;
        Reason reason;
    }
}
