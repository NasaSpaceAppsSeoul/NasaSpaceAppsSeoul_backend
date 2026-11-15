package com.burnline.common.load;

import com.burnline.common.config.BurnlineProperties;
import com.burnline.common.domain.Country;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 파이프라인 출력 읽기 (burnline_backend_data/). 파일에는 NaN이 들어 있어서(예: ratio_to_median)
 * 그대로는 jsonb에 못 넣는다 → NaN·Infinity를 null로 바꿔 돌려준다.
 */
@Component
public class SourceFiles {

    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .build();

    private final Path dir;

    public SourceFiles(BurnlineProperties props) {
        this.dir = Path.of(props.data().dir()).toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    /** output/{country}/{name} */
    public JsonNode output(Country country, String name) {
        return read(dir.resolve("output").resolve(country.name()).resolve(name));
    }

    /** boundaries/{country}/{name} */
    public JsonNode boundaries(Country country, String name) {
        return read(dir.resolve("boundaries").resolve(country.name()).resolve(name));
    }

    /**
     * NOAA-20 정식 아카이브(SP)의 마지막 날짜. hotspots_clean.csv의 src 열(archive/nrt)로 가른다.
     * 파이프라인 품질 규칙의 n20_archive_end와 같은 기준이다. 파일이 없으면 empty.
     */
    public Optional<LocalDate> noaa20ArchiveEnd(Country country) {
        Path csv = dir.resolve("output").resolve(country.name()).resolve("hotspots_clean.csv");
        if (!Files.exists(csv)) {
            return Optional.empty();
        }
        try (var lines = Files.lines(csv, StandardCharsets.UTF_8)) {
            var it = lines.iterator();
            List<String> head = Arrays.asList(it.next().split(","));
            int date = head.indexOf("acq_date");
            int sat = head.indexOf("satellite");
            int src = head.indexOf("src");
            String max = null;
            while (it.hasNext()) {
                String[] f = it.next().split(",");
                if ("N20".equals(f[sat]) && "archive".equals(f[src]) && (max == null || f[date].compareTo(max) > 0)) {
                    max = f[date];
                }
            }
            return Optional.ofNullable(max).map(LocalDate::parse);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + csv, e);
        }
    }

    /**
     * 명세 7-1 generated_at (시각 포함). meta.json에 generated_at이 있으면 그것을, 없으면 파이프라인이
     * meta.json을 쓴 시각(파일 수정 시각, 한국 시간)을 쓴다. 재시작해도 바뀌지 않는다.
     * TODO: 파이프라인이 meta.json에 generated_at을 넣으면 그 값이 자동으로 우선한다.
     */
    public String generatedAt(Country country, JsonNode meta) {
        if (meta.path("generated_at").isTextual()) {
            return meta.get("generated_at").asText();
        }
        Path p = dir.resolve("output").resolve(country.name()).resolve("meta.json");
        try {
            return Files.getLastModifiedTime(p).toInstant().atZone(ZoneId.of("Asia/Seoul"))
                    .truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stat " + p, e);
        }
    }

    public boolean exists() {
        return Files.isDirectory(dir.resolve("output"));
    }

    private static JsonNode read(Path path) {
        try {
            return sanitize(LENIENT.readTree(path.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    static JsonNode sanitize(JsonNode node) {
        if (node.isDouble() || node.isFloat()) {
            double v = node.asDouble();
            return Double.isNaN(v) || Double.isInfinite(v) ? NullNode.getInstance() : node;
        }
        if (node instanceof ObjectNode obj) {
            obj.properties().forEach(e -> e.setValue(sanitize(e.getValue())));
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, sanitize(arr.get(i)));
            }
        }
        return node;
    }

    /**
     * 한국 시·군·구 한글 이름 (shapeID → 이름). 원본에는 영문 이름만 있어서 따로 만든 표다.
     * 만든 방법: 저장소 루트 파이프라인의 통계청 행정구역 이름을 같은 geoBoundaries 경계 중심점으로 맞추고,
     * 로마자 표기로 대조한 뒤 옹진군·미추홀구를 손으로 고쳤다 (229곳).
     */
    public static Map<String, String> koreanDistrictNames() {
        Map<String, String> names = new HashMap<>();
        try (var in = Objects.requireNonNull(SourceFiles.class.getResourceAsStream("/names/KOR_district_ko.csv"));
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine(); // 머리줄
            for (String line; (line = reader.readLine()) != null; ) {
                String[] parts = line.split(",", 3);
                names.put(parts[0], parts[1]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }
}
