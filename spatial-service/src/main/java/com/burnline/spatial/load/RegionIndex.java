package com.burnline.spatial.load;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 점 → 하위 구역 배정 (공간 DB 없이). 파이프라인 ⑭와 같은 규칙:
 * 경계 안이면 그 구역, 밖이면 {@link #NEAREST_MAX}(0.03°, 약 3 km) 안에서 꼭짓점이 가장 가까운 구역, 아니면 null.
 */
public final class RegionIndex {

    public static final double NEAREST_MAX = 0.03;

    /** 구역 하나: 다각형들(바깥 고리 + 구멍), 경계 상자, 꼭짓점 평균 */
    record Shape(String id, List<double[][][]> polygons, double[] bbox, double[] center) {
    }

    private final List<Shape> shapes = new ArrayList<>();

    /**
     * @param features 원본 경계 FeatureCollection의 features (shapeID 속성)
     * @param keep     배정 대상 구역 ID (regions.geojson에 있는 것만)
     */
    public RegionIndex(Iterable<JsonNode> features, Set<String> keep) {
        for (JsonNode f : features) {
            String id = f.path("properties").path("shapeID").asText();
            if (!keep.contains(id)) {
                continue;
            }
            JsonNode g = f.get("geometry");
            List<double[][][]> polys = new ArrayList<>();
            JsonNode coords = g.get("coordinates");
            if ("Polygon".equals(g.path("type").asText())) {
                polys.add(rings(coords));
            } else {
                coords.forEach(p -> polys.add(rings(p)));
            }
            double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            double sx = 0, sy = 0;
            long n = 0;
            for (double[][][] poly : polys) {
                for (double[] p : poly[0]) {
                    b[0] = Math.min(b[0], p[0]);
                    b[1] = Math.min(b[1], p[1]);
                    b[2] = Math.max(b[2], p[0]);
                    b[3] = Math.max(b[3], p[1]);
                    sx += p[0];
                    sy += p[1];
                    n++;
                }
            }
            shapes.add(new Shape(id, polys, b, new double[]{sx / n, sy / n}));
        }
    }

    private static double[][][] rings(JsonNode poly) {
        double[][][] out = new double[poly.size()][][];
        for (int r = 0; r < poly.size(); r++) {
            JsonNode ring = poly.get(r);
            out[r] = new double[ring.size()][];
            for (int i = 0; i < ring.size(); i++) {
                out[r][i] = new double[]{ring.get(i).get(0).asDouble(), ring.get(i).get(1).asDouble()};
            }
        }
        return out;
    }

    /** 경계 파일 안 순서 (같은 표를 받은 구역끼리 고를 때). 구역 밖("")은 가장 앞 — 파이프라인의 -1과 같다 */
    public int order(String id) {
        if (id == null) {
            return Integer.MAX_VALUE;
        }
        if (id.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < shapes.size(); i++) {
            if (shapes.get(i).id().equals(id)) {
                return i;
            }
        }
        return Integer.MAX_VALUE - 1;
    }

    /** 구역 ID → 꼭짓점 평균 [lon, lat] (인사이트 50 km 계산 기준, 파이프라인과 같음) */
    public Map<String, double[]> centers() {
        Map<String, double[]> m = new LinkedHashMap<>();
        shapes.forEach(s -> m.put(s.id(), s.center()));
        return m;
    }

    public String locate(double lon, double lat) {
        for (Shape s : shapes) {
            double[] b = s.bbox();
            if (lon < b[0] || lon > b[2] || lat < b[1] || lat > b[3]) {
                continue;
            }
            for (double[][][] poly : s.polygons()) {
                if (inRing(poly[0], lon, lat)) {
                    boolean hole = false;
                    for (int h = 1; h < poly.length && !hole; h++) {
                        hole = inRing(poly[h], lon, lat);
                    }
                    if (!hole) {
                        return s.id();
                    }
                }
            }
        }
        return nearest(lon, lat);
    }

    private String nearest(double lon, double lat) {
        String best = null;
        double bestD = NEAREST_MAX;
        for (Shape s : shapes) {
            double[] b = s.bbox();
            if (lon < b[0] - NEAREST_MAX || lon > b[2] + NEAREST_MAX || lat < b[1] - NEAREST_MAX || lat > b[3] + NEAREST_MAX) {
                continue;
            }
            for (double[][][] poly : s.polygons()) {
                for (double[] p : poly[0]) {
                    double d = Math.hypot(p[0] - lon, p[1] - lat);
                    if (d <= bestD) {
                        bestD = d;
                        best = s.id();
                    }
                }
            }
        }
        return best;
    }

    /** 짝홀 규칙 (ray casting) */
    static boolean inRing(double[][] ring, double x, double y) {
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                in = !in;
            }
        }
        return in;
    }
}
