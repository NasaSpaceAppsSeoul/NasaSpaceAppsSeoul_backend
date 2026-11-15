package com.burnline.common.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 명세 2장 코드값. 응답에는 이 값(영문)만 쓰고 한글 문구는 프론트가 만든다.
 * 상수 이름을 명세 값과 똑같이 써서 JSON에 그대로 나가게 했다.
 */
public final class Codes {

    private Codes() {
    }

    /** nat 나라 / prov 시·도 / dist 시·군·구 (CAL·BC는 주 하나라 prov를 따로 보이지 않음) */
    public enum Level { nat, prov, dist }

    /** 위에서부터 먼저 맞는 하나. 범위 = 15~85 퍼센타일 + 2√평년 */
    public enum Chip { record, above, normal, below }

    /** VIIRS 연평균(최대 해 제외) ≥ 11.1 / 미만 / 2012년 이후 0 */
    public enum RegionType { sufficient, sparse, none }

    /** 시·군·구 → 소속 시·도 → 중심점이 가장 가까운 "충분" 시·도 */
    public enum JudgeUnit {
        SELF("self"), PROVINCE("province"), NEARBY_PROVINCE("nearby_province");

        private final String value;

        JudgeUnit(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    /** 변환 오차 10% / 30% 기준, 평년 5칸 미만이면 한 단계 낮춤 */
    public enum Quality { high, medium, low }

    /** 진행 중인 달은 partial, SP가 없는 달은 nrt */
    public enum Status { final_, partial, nrt;

        @JsonValue
        public String value() {
            return this == final_ ? "final" : name();
        }
    }

    /** 나라와 경기·경북·충남은 25년, 나머지는 2012~ */
    public enum Basis { harmonized_25y, viirs_2012 }

    /** 명세 5장 빈 상태 */
    public enum Reason { PRE_2012_DISTRICT, NO_DETECTION, ZERO_BASELINE, IN_PROGRESS, NO_RECORD }

    /** 달별 실제 관측 위성 */
    public enum Source {
        NOAA20("NOAA-20"), SNPP("S-NPP→NOAA-20"), MODIS("MODIS→NOAA-20"), TERRA("Terra→NOAA-20");

        private final String value;

        Source(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }
}
