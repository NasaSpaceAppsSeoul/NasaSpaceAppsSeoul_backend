package com.burnline.common.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 명세 1장: country = KOR, CAL, BC, GRC, CHL, NSW. 나라(주) 전체를 가리키는 지역 ID도 같은 문자열이다.
 * 명세 2장 나라별 이름표(/meta.labels)도 여기 둔다.
 */
public enum Country {
    KOR("대한민국", "전국", "Republic of Korea", "시·도", "시·군·구", false, "산림청",
            "geoBoundaries (CC BY 3.0) + 영광군 보강", "geoBoundaries (CC BY 3.0)", List.of(3, 10, 30),
            "UTC+9", "한국 표준시 (UTC+9)", 1),
    CAL("캘리포니아", "캘리포니아", "California", "주", "카운티", true, "CAL FIRE",
            "geoBoundaries USA (미국 인구조사국, 퍼블릭 도메인)", "geoBoundaries USA (미국 인구조사국, 퍼블릭 도메인)",
            List.of(3, 8, 18), "solar", "픽셀 경도 기준 태양시", 1),
    BC("브리티시컬럼비아", "BC", "British Columbia", "주", "지역구", true, "BC Wildfire Service",
            "BC Data Catalogue regional district (OGL–BC)", "BC Data Catalogue regional district (OGL–BC)",
            List.of(2, 8, 47), "solar", "픽셀 경도 기준 태양시", 1),
    // 아래 세 지역의 bins는 CAL·BC와 같은 규칙: 2012-02 이후 0이 아닌 구역-달 화재 칸의 25·50·75 퍼센타일
    GRC("그리스", "그리스", "Greece", "주", "시군", false, "그리스 소방청",
            "geoBoundaries GRC (EuroGeographics, CC BY 4.0)", "geoBoundaries (EuroGeographics, CC BY 4.0)", List.of(1, 2, 3),
            "solar", "픽셀 경도 기준 태양시", 1),
    CHL("칠레", "칠레", "Chile", "주", "시군", false, "CONAF",
            "geoBoundaries CHL (칠레 국회도서관 BCN, CC BY 3.0 IGO)", "geoBoundaries (BCN, CC BY 3.0 IGO)", List.of(1, 2, 5),
            "solar", "픽셀 경도 기준 태양시", 7),
    NSW("뉴사우스웨일스", "NSW", "New South Wales", "주", "지방정부", true, "NSW NPWS",
            "geoBoundaries AUS (호주 통계청 ABS 2022 지방정부, CC BY 4.0)", "geoBoundaries (ABS, CC BY 4.0)", List.of(3, 10, 31),
            "solar", "픽셀 경도 기준 태양시", 7);

    private final String labelKo;
    private final String shortLabel;
    private final String nameEn;
    private final String lv1;
    private final String lv2;
    private final boolean single;
    private final String official;
    private final String boundary;
    private final String boundaryLicense;
    private final List<Integer> bins;
    private final String timeBasis;
    private final String timeBasisKo;
    private final int seasonStart;

    Country(String labelKo, String shortLabel, String nameEn, String lv1, String lv2, boolean single,
            String official, String boundary, String boundaryLicense, List<Integer> bins,
            String timeBasis, String timeBasisKo, int seasonStart) {
        this.labelKo = labelKo;
        this.shortLabel = shortLabel;
        this.nameEn = nameEn;
        this.lv1 = lv1;
        this.lv2 = lv2;
        this.single = single;
        this.official = official;
        this.boundary = boundary;
        this.boundaryLicense = boundaryLicense;
        this.bins = bins;
        this.timeBasis = timeBasis;
        this.timeBasisKo = timeBasisKo;
        this.seasonStart = seasonStart;
    }

    /** 산불 시즌 시작 월. 북반구 1(1~12월), 남반구 7(7월~다음 해 6월). 올해 누적(ytd)이 이 달부터 센다 */
    public int seasonStart() {
        return seasonStart;
    }

    /** 명세 6장 라이선스 표기: /meta.sources에 넣는 경계 출처 이름 (보강 설명은 빼고 라이선스만) */
    public String boundaryLicense() {
        return boundaryLicense;
    }

    /** 명세 1장 날짜 기준: 현지 날짜 (한국 UTC+9, 나머지 지역은 파이프라인과 같이 픽셀 경도 기준 태양시) */
    public Map<String, Object> timeBasis() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", timeBasis);
        m.put("label_ko", timeBasisKo);
        return m;
    }

    public String nationalId() {
        return name();
    }

    public String labelKo() {
        return labelKo;
    }

    public String nameEn() {
        return nameEn;
    }

    /** 상위 구역이 주 하나뿐이라 level prov를 따로 보여 주지 않는다 (CAL·BC·NSW) */
    public boolean single() {
        return single;
    }

    /** 명세 7-13 /meta.labels */
    public Map<String, Object> labels() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("country", labelKo);
        m.put("short", shortLabel);
        m.put("lv1", lv1);
        m.put("lv2", lv2);
        m.put("single", single);
        m.put("official", official);
        m.put("boundary", boundary);
        m.put("bins", bins);
        m.put("season_start", seasonStart);
        return m;
    }
}
