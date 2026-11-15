package com.burnline.spatial.load;

import com.burnline.common.domain.Country;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 지역별 공식 산불 기록을 같은 모양으로: 날짜, 장소, 원인(한국어), 면적(ha), 구역 ID.
 * <ul>
 *   <li>KOR 산림청 산불통계.xlsx: 좌표가 없어 "시·도 시·군·구 읍·면" 장소 이름으로 시·군·구를 맞춘다</li>
 *   <li>CAL CAL FIRE FRAP 화재 경계: 경계 중심점 좌표로 카운티 배정, 에이커 → ha</li>
 *   <li>BC BC Wildfire Service: FIRE_TYPE = Fire만, 좌표로 지역구 배정, 날짜가 잘못된 줄은 버림</li>
 *   <li>GRC·CHL·NSW: 파이프라인이 정리한 같은 열의 CSV ({@link #standard})</li>
 * </ul>
 */
public final class OfficialFireReader {

    /** year = 기관이 집계하는 연도 (BC FIRE_YEAR, CAL YEAR_, 한국은 발생일 연도) */
    public record Fire(LocalDate date, int year, String place, String cause, double areaHa, String regionId, String source) {
    }

    /** CAL FIRE 원인 코드 (FRAP CAUSE) */
    static final Map<Integer, String> CAL_CAUSE = Map.ofEntries(
            Map.entry(1, "번개"), Map.entry(2, "장비 사용"), Map.entry(3, "흡연"), Map.entry(4, "캠프파이어"),
            Map.entry(5, "쓰레기 소각"), Map.entry(6, "철도"), Map.entry(7, "방화"), Map.entry(8, "불장난"),
            Map.entry(9, "기타"), Map.entry(10, "차량"), Map.entry(11, "전선"), Map.entry(12, "소방 훈련"),
            Map.entry(13, "기타 훈련"), Map.entry(14, "원인 미상"), Map.entry(15, "건물"), Map.entry(16, "항공기"),
            Map.entry(18, "처방 화입 확산"), Map.entry(19, "불법 캠프파이어"));

    private OfficialFireReader() {
    }

    public static List<Fire> read(Country c, Path externalDir, RegionIndex index, List<String[]> korDistricts) {
        return switch (c) {
            case KOR -> korea(externalDir.resolve("산불통계.xlsx"), korDistricts);
            case CAL -> california(externalDir.resolve("cal_fire_perimeters_2001_2025.csv"), index);
            case BC -> britishColumbia(externalDir.resolve("bc_fire_incidents_2001_2025.csv"), index);
            case GRC -> standard(externalDir.resolve("grc_fire_service_2000_2024.csv"), index, "그리스 소방청",
                    r -> !"prefecture".equals(r.get("loc_quality")), r -> intOrNull(r.get("year")));
            case CHL -> standard(externalDir.resolve("chl_conaf_large_fires_1985_2024.csv"), index, "CONAF",
                    r -> true, r -> intOrNull(head(r.get("season"), 4)));
            case NSW -> standard(externalDir.resolve("nsw_npws_wildfires_2000_2026.csv"), index, "NSW NPWS",
                    r -> true, r -> intOrNull(head(r.get("fire_year"), 4)));
        };
    }

    // ---------------------------------------------------------------- 그리스 · 칠레 · NSW (정리한 CSV, 같은 열)

    /**
     * 파이프라인이 정리한 공통 열: date, lat, lon, cause, area_ha, place.
     * <ul>
     *   <li>GRC 그리스 소방청 2000~2024: loc_quality = prefecture인 줄(2000~2008 대부분)은 좌표가 광역 대표점이라
     *       구역을 배정하지 않는다 (연 면적에는 넣음). 집계 연도 = year</li>
     *   <li>CHL CONAF 200 ha 이상 산불 1985~: 일부 좌표는 시군 대표점. 집계 연도 = 시즌 시작 연도 (7월~다음 해 6월)</li>
     *   <li>NSW NPWS 화재 이력(산불만): 날짜 없는 줄(대부분 2000~2003)은 버림. 집계 연도 = 시즌 시작 연도(fire_year 앞 4자리)</li>
     * </ul>
     * 원인 열은 세 자료 모두 비어 있어 "원인 미상"이 된다.
     */
    static List<Fire> standard(Path csv, RegionIndex index, String source,
                               Predicate<CSVRecord> locatable, Function<CSVRecord, Integer> agencyYear) {
        if (!java.nio.file.Files.exists(csv)) {
            // 데이터 패키지에 파일이 빠지면 조용히 넘어가지 않게 로그를 남긴다 (공식 기록 표·감지율이 비게 됨)
            org.slf4j.LoggerFactory.getLogger(OfficialFireReader.class).warn("official fire file missing, skipping: {}", csv);
            return List.of();
        }
        List<Fire> out = new ArrayList<>();
        for (CSVRecord r : records(csv)) {
            LocalDate date = parseDate(r.get("date"));
            Double lat = num(r.get("lat")), lon = num(r.get("lon"));
            if (date == null || lat == null || lon == null) {
                continue;
            }
            String cause = r.get("cause");
            Double ha = num(r.get("area_ha"));
            Integer y = agencyYear.apply(r);
            String region = locatable.test(r) ? index.locate(lon, lat) : null;
            out.add(new Fire(date, y == null ? date.getYear() : y, r.get("place").trim(),
                    cause == null || cause.isBlank() ? "원인 미상" : cause, ha == null ? 0 : ha, region, source));
        }
        return out;
    }

    private static String head(String s, int n) {
        return s == null || s.length() < n ? null : s.substring(0, n);
    }

    private static Integer intOrNull(String s) {
        Double d = num(s);
        return d == null ? null : d.intValue();
    }

    // ---------------------------------------------------------------- 한국 (이름 맞추기)

    /**
     * @param districts [id, label_ko, province] (names/KOR_district_ko.csv)
     */
    static List<Fire> korea(Path xlsx, List<String[]> districts) {
        KoreanMatcher matcher = new KoreanMatcher(districts);
        List<Fire> out = new ArrayList<>();
        DataFormatter fmt = new DataFormatter(Locale.ROOT);
        try (Workbook wb = WorkbookFactory.create(xlsx.toFile(), null, true)) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;   // 머리줄: 발생일, 발생장소, 원인, 피해면적(ha)
                }
                LocalDate date = dateOf(row.getCell(0), fmt);
                String place = fmt.formatCellValue(row.getCell(1)).trim();
                if (date == null || place.isEmpty()) {
                    continue;
                }
                String cause = fmt.formatCellValue(row.getCell(2)).trim();
                double ha = number(row.getCell(3), fmt);
                String[] tok = place.split("\\s+");
                String region = matcher.match(tok[0], tok.length > 1 ? tok[1] : "");
                out.add(new Fire(date, date.getYear(), place, cause.isEmpty() ? "원인 미상" : cause, ha, region, "산림청"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + xlsx, e);
        }
        return out;
    }

    private static LocalDate dateOf(Cell cell, DataFormatter fmt) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String s = fmt.formatCellValue(cell).trim();
        return s.length() >= 10 ? parseDate(s.substring(0, 10)) : null;
    }

    private static double number(Cell cell, DataFormatter fmt) {
        if (cell == null) {
            return 0;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        try {
            return Double.parseDouble(fmt.formatCellValue(cell).replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 산림청 장소 "경북 의성 안계" → 시·군·구 ID. 같은 시·도 안에서 "의성" = "의성군"(끝 글자 시·군·구를 뗀 이름)으로 맞춘다.
     * 이름으로 맞지 않는 곳(파이프라인 KFS_FIX): 2023년 대구로 편입된 군위, 인천의 생활권 이름 영종·검단, 시·군·구가 하나인 세종.
     * 산림청 자료는 광주·전남을 "전남광주"로 묶어 적는다.
     */
    static final class KoreanMatcher {

        private final Map<String, List<String[]>> byProvince = new HashMap<>();
        private final Map<String, String> fixes = new HashMap<>();

        KoreanMatcher(List<String[]> districts) {
            for (String[] d : districts) {
                byProvince.computeIfAbsent(d[2], k -> new ArrayList<>()).add(d);
            }
            fix("대구 군위", "대구", "군위군");
            fix("경북 군위", "대구", "군위군");
            fix("인천 영종", "인천", "중구");
            fix("인천 검단", "인천", "서구");
        }

        private void fix(String key, String province, String label) {
            byProvince.getOrDefault(province, List.of()).stream()
                    .filter(d -> d[1].equals(label)).findFirst()
                    .ifPresent(d -> fixes.put(key, d[0]));
        }

        String match(String province, String sgg) {
            String fixed = fixes.get(province + " " + sgg);
            if (fixed != null) {
                return fixed;
            }
            List<String> provinces = "전남광주".equals(province) ? List.of("전남", "광주") : List.of(province);
            if ("세종".equals(province)) {
                List<String[]> s = byProvince.getOrDefault("세종", List.of());
                return s.isEmpty() ? null : s.get(0)[0];
            }
            // 1) "의성" = "의성군"  2) "창원" ⊂ "창원시"  3) 일반구가 붙은 이름 "수원장안"·"포항북" ⊃ "수원", "서해" ⊃ "서"(서구)
            String prefixHit = null, stemHit = null;
            int stemLen = 0;
            for (String p : provinces) {
                for (String[] d : byProvince.getOrDefault(p, List.of())) {
                    String label = d[1];
                    String stem = label.length() > 1 ? label.substring(0, label.length() - 1) : label;
                    if (stem.equals(sgg) || label.equals(sgg)) {
                        return d[0];
                    }
                    if (prefixHit == null && !sgg.isEmpty() && label.startsWith(sgg)) {
                        prefixHit = d[0];
                    }
                    if (!sgg.isEmpty() && sgg.startsWith(stem) && stem.length() > stemLen) {
                        stemHit = d[0];
                        stemLen = stem.length();
                    }
                }
            }
            return prefixHit != null ? prefixHit : stemHit;
        }
    }

    // ---------------------------------------------------------------- 캘리포니아 · BC (좌표)

    static List<Fire> california(Path csv, RegionIndex index) {
        List<Fire> out = new ArrayList<>();
        for (CSVRecord r : records(csv)) {
            LocalDate date = parseDate(r.get("ALARM_DATE"));
            Double lat = num(r.get("LATITUDE")), lon = num(r.get("LONGITUDE"));
            if (date == null || lat == null || lon == null) {
                continue;
            }
            Double code = num(r.get("CAUSE"));
            String cause = code == null ? "원인 미상" : CAL_CAUSE.getOrDefault(code.intValue(), "기타");
            Double acres = num(r.get("GIS_ACRES"));
            Double y = num(r.get("YEAR_"));
            out.add(new Fire(date, y == null ? date.getYear() : y.intValue(), title(r.get("FIRE_NAME")), cause, acres == null ? 0 : acres * 0.404686,
                    index.locate(lon, lat), "CAL FIRE"));
        }
        return out;
    }

    static List<Fire> britishColumbia(Path csv, RegionIndex index) {
        List<Fire> out = new ArrayList<>();
        for (CSVRecord r : records(csv)) {
            if (!"Fire".equals(r.get("FIRE_TYPE"))) {
                continue;   // 연기 확인·오인 신고·계획 소각 등은 뺀다
            }
            LocalDate date = parseDate(r.get("IGNITION_DATE"));
            Double lat = num(r.get("LATITUDE")), lon = num(r.get("LONGITUDE"));
            if (date == null || date.getYear() < 2001 || date.getYear() > 2026 || lat == null || lon == null) {
                continue;   // 원자료의 잘못된 날짜(예: 0212-01-12)
            }
            String cause = switch (r.get("FIRE_CAUSE")) {
                case "Lightning" -> "번개";
                case "Person" -> "사람";
                default -> "원인 미상";
            };
            Double ha = num(r.get("CURRENT_SIZE"));
            Double y = num(r.get("FIRE_YEAR"));
            out.add(new Fire(date, y == null ? date.getYear() : y.intValue(), title(r.get("GEOGRAPHIC_DESCRIPTION")), cause, ha == null ? 0 : ha,
                    index.locate(lon, lat), "BC Wildfire Service"));
        }
        return out;
    }

    private static Iterable<CSVRecord> records(Path csv) {
        try {
            Reader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
            return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(in).getRecords();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + csv, e);
        }
    }

    static LocalDate parseDate(String s) {
        if (s == null || s.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    static Double num(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isNaN(v) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "GIFFORD" → "Gifford" (파이프라인 str.title()과 같은 모양) */
    static String title(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        boolean up = true;
        for (char ch : s.trim().toCharArray()) {
            b.append(up ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
            up = !Character.isLetter(ch);
        }
        return b.toString();
    }
}
