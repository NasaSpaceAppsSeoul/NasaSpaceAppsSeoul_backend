package com.burnline.common.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** GeoJSON 계산 (공간 DB 없이): 좌표 반올림, 중심점, 경계 상자 */
public final class Geo {

    private Geo() {
    }

    public static double round3(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    /** 명세 1장: 좌표 소수점 3자리 */
    public static JsonNode roundCoordinates(JsonNode geometry) {
        ObjectNode copy = geometry.deepCopy();
        copy.set("coordinates", roundArray(copy.get("coordinates")));
        return copy;
    }

    private static JsonNode roundArray(JsonNode node) {
        if (node.isNumber()) {
            return DoubleNode.valueOf(round3(node.asDouble()));
        }
        ArrayNode out = ((ArrayNode) node).arrayNode();
        node.forEach(child -> out.add(roundArray(child)));
        return out;
    }

    /** Polygon / MultiPolygon의 바깥 고리들 */
    public static List<JsonNode> outerRings(JsonNode geometry) {
        List<JsonNode> rings = new ArrayList<>();
        JsonNode coords = geometry.get("coordinates");
        if ("Polygon".equals(geometry.path("type").asText())) {
            rings.add(coords.get(0));
        } else {
            coords.forEach(poly -> rings.add(poly.get(0)));
        }
        return rings;
    }

    /** [minLon, minLat, maxLon, maxLat] */
    public static double[] bbox(JsonNode geometry) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (JsonNode ring : outerRings(geometry)) {
            for (JsonNode p : ring) {
                b[0] = Math.min(b[0], p.get(0).asDouble());
                b[1] = Math.min(b[1], p.get(1).asDouble());
                b[2] = Math.max(b[2], p.get(0).asDouble());
                b[3] = Math.max(b[3], p.get(1).asDouble());
            }
        }
        return b;
    }

    public static double[] union(double[] a, double[] b) {
        if (a == null) {
            return b.clone();
        }
        return new double[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    /** 면적 가중 중심점 (바깥 고리 기준). 면적이 0이면 상자 중심. */
    public static double[] centroid(JsonNode geometry) {
        double area = 0, cx = 0, cy = 0;
        for (JsonNode ring : outerRings(geometry)) {
            int n = ring.size();
            for (int i = 0; i < n; i++) {
                JsonNode p = ring.get(i);
                JsonNode q = ring.get((i + 1) % n);
                double x1 = p.get(0).asDouble(), y1 = p.get(1).asDouble();
                double x2 = q.get(0).asDouble(), y2 = q.get(1).asDouble();
                double c = x1 * y2 - x2 * y1;
                area += c;
                cx += (x1 + x2) * c;
                cy += (y1 + y2) * c;
            }
        }
        if (Math.abs(area) < 1e-12) {
            double[] b = bbox(geometry);
            return new double[]{round3((b[0] + b[2]) / 2), round3((b[1] + b[3]) / 2)};
        }
        return new double[]{round3(cx / (3 * area)), round3(cy / (3 * area))};
    }

    /** 위경도 거리 근사 (km) */
    public static double km(double[] a, double[] b) {
        double lat = Math.toRadians((a[1] + b[1]) / 2);
        double dx = (a[0] - b[0]) * 111.32 * Math.cos(lat);
        double dy = (a[1] - b[1]) * 110.57;
        return Math.hypot(dx, dy);
    }
}
