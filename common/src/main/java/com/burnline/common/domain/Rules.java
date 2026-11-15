package com.burnline.common.domain;

import com.burnline.common.domain.Codes.Basis;
import com.burnline.common.domain.Codes.JudgeUnit;
import com.burnline.common.domain.Codes.Quality;
import com.burnline.common.domain.Codes.RegionType;

/**
 * 원본(파이프라인 출력) 값 → 명세 코드값. 두 서비스의 적재기가 같은 규칙을 쓰도록 한곳에 둔다.
 */
public final class Rules {

    /** S-NPP 첫 완전한 달. 이전 달은 MODIS 변환값 → 시·군·구는 값 없음(PRE_2012_DISTRICT) */
    public static final String VIIRS_START = "2012-02";
    /** NOAA-20 시작. 명세 2장: 시·도는 이 달 전도 추정값 */
    public static final String NOAA20_START = "2018-04";

    private Rules() {
    }

    /** sufficiency: 충분 / 부족 / 감지 없음 (영문: sufficient/frequent / none / sparse/rare) */
    public static RegionType regionType(String sufficiency) {
        return switch (sufficiency == null ? "" : sufficiency) {
            case "충분", "sufficient", "frequent" -> RegionType.sufficient;
            case "감지 없음", "none" -> RegionType.none;
            default -> RegionType.sparse;
        };
    }

    /** basis: "보정 25년" / "VIIRS 2012~" */
    public static Basis basis(String basis) {
        return basis != null && basis.startsWith("보정") ? Basis.harmonized_25y : Basis.viirs_2012;
    }

    /** fallback.ref_from: "시·도" / "인접 시·도" (없으면 자체 판정) */
    public static JudgeUnit judgeUnit(String refFrom) {
        if (refFrom == null) {
            return JudgeUnit.SELF;
        }
        return refFrom.startsWith("인접") ? JudgeUnit.NEARBY_PROVINCE : JudgeUnit.PROVINCE;
    }

    /** risk_from: "시·군·구" / "시·도 (경북)" / "인접 시·도 (강원)" (영문: "self" / "province" / "nearby_province") */
    public static String riskFrom(String riskFrom) {
        if (riskFrom == null || riskFrom.startsWith("시·군·구") || "self".equals(riskFrom)) {
            return JudgeUnit.SELF.value();
        }
        if ("nearby_province".equals(riskFrom)) {
            return JudgeUnit.NEARBY_PROVINCE.value();
        }
        return judgeUnit(riskFrom).value();
    }

    /** 품질: 높음 / 보통 / 낮음 (영문: high / medium / low) */
    public static Quality quality(String label) {
        if (label == null) {
            return null;
        }
        return switch (label) {
            case "높음", "high" -> Quality.high;
            case "보통", "medium" -> Quality.medium;
            default -> Quality.low;
        };
    }

    /**
     * 공식 기록 대비 단절 검정의 inconclusive 기준 (A /meta·B /landing 공통).
     * 보정 후 "2012 뒤 / 앞" 비율이 0.75~1.33 밖이면 결론을 내리지 않는다 (예: BC 2.14 → true).
     */
    public static final double OFFICIAL_OK_LOW = 0.75;
    public static final double OFFICIAL_OK_HIGH = 1.33;

    public static boolean inconclusive(Double afterRatio) {
        return afterRatio != null && (afterRatio < OFFICIAL_OK_LOW || afterRatio > OFFICIAL_OK_HIGH);
    }

    /** 같은 달 평년(중앙값)이 이보다 작으면 품질 한 단계 낮춤 (명세 2장, 파이프라인 LOW_COUNT) */
    public static final double LOW_COUNT = 5;

    /** 명세 2장 quality: 평년 5칸 미만이면 한 단계 낮춤 (high → medium → low) */
    public static Quality lowerIfLowBaseline(Quality q, Double median) {
        if (q == null || median == null || median >= LOW_COUNT) {
            return q;
        }
        return q == Quality.high ? Quality.medium : Quality.low;
    }

    /**
     * 명세 2장 status: 진행 중인 달은 partial, SP가 없는 달(NOAA-20 아카이브가 끝난 달 뒤)은 nrt, 나머지 final.
     * 아카이브 끝 날짜가 든 달은 SP로 본다 (파이프라인 품질 규칙과 같음).
     */
    public static String status(boolean partial, String month, String archiveEndMonth) {
        if (partial) {
            return "partial";
        }
        return archiveEndMonth != null && month.compareTo(archiveEndMonth) > 0 ? "nrt" : "final";
    }

    public static Quality quality(int index) {
        return switch (index) {
            case 2 -> Quality.high;
            case 1 -> Quality.medium;
            default -> Quality.low;
        };
    }

    /**
     * 명세 2장 estimated: 2012-02 전 달은 추정값 (시·도는 2018-04 전도).
     * TODO(A): 시·도 규칙은 명세 표가 깨져 있어 이렇게 읽었다. 기획과 확인.
     */
    public static boolean estimated(Codes.Level level, String month) {
        String cut = level == Codes.Level.prov ? NOAA20_START : VIIRS_START;
        return month.compareTo(cut) < 0;
    }
}
