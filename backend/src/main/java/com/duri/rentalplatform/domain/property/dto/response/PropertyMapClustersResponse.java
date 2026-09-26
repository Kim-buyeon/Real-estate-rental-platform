package com.duri.rentalplatform.domain.property.dto.response;

import com.duri.rentalplatform.domain.property.vo.MapClusterCellRow;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 지도 묶음. API 명세서(매물) 1.12.
 *
 * <p>묶지 않았으면({@code clustered = false}) {@code clusters} 는 빈 목록이고 영역의 매물이 전부 {@code markers} 다.
 */
public record PropertyMapClustersResponse(
        long total,
        boolean clustered,
        List<Cluster> clusters,
        List<PropertyMarkerResponse> markers
) {

    /** 칸 경계 좌표의 소수 자릿수. 좌표 컬럼(NUMERIC(10, 7))과 같게 맞춘다. */
    private static final int COORDINATE_SCALE = 7;

    public static PropertyMapClustersResponse unclustered(long total, List<PropertyMarkerResponse> markers) {
        return new PropertyMapClustersResponse(total, false, List.of(), markers);
    }

    public static PropertyMapClustersResponse clustered(long total, List<Cluster> clusters,
            List<PropertyMarkerResponse> markers) {
        return new PropertyMapClustersResponse(total, true, clusters, markers);
    }

    /**
     * 묶음 한 칸.
     *
     * @param key 칸 식별자 {@code 행:열}
     */
    public record Cluster(
            String key,
            BigDecimal latitude,
            BigDecimal longitude,
            int count,
            GradeCounts gradeCounts,
            BigDecimal minLat,
            BigDecimal maxLat,
            BigDecimal minLng,
            BigDecimal maxLng
    ) {

        /** 칸 경계는 {@code 최소 + 칸 번호 × 칸 크기} — 칸 번호를 매긴 식과 같은 double 연산으로 구한다. */
        public static Cluster of(MapClusterCellRow row, double minLat, double minLng,
                double cellLat, double cellLng) {
            double cellMinLat = minLat + row.rowIndex() * cellLat;
            double cellMinLng = minLng + row.colIndex() * cellLng;
            return new Cluster(
                    row.rowIndex() + ":" + row.colIndex(),
                    row.latitude(),
                    row.longitude(),
                    Math.toIntExact(nz(row.count())),
                    GradeCounts.of(row),
                    coordinate(cellMinLat),
                    coordinate(cellMinLat + cellLat),
                    coordinate(cellMinLng),
                    coordinate(cellMinLng + cellLng));
        }

        private static BigDecimal coordinate(double value) {
            return BigDecimal.valueOf(value).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 등급별 매물 수. 직렬화 키는 명세의 등급 상수명 그대로 대문자다. */
    public record GradeCounts(
            @JsonProperty("SAFE") int safe,
            @JsonProperty("CAUTION") int caution,
            @JsonProperty("DANGER") int danger,
            @JsonProperty("UNANALYZED") int unanalyzed
    ) {

        static GradeCounts of(MapClusterCellRow row) {
            return new GradeCounts(
                    Math.toIntExact(nz(row.safeCount())),
                    Math.toIntExact(nz(row.cautionCount())),
                    Math.toIntExact(nz(row.dangerCount())),
                    Math.toIntExact(nz(row.unanalyzedCount())));
        }
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
