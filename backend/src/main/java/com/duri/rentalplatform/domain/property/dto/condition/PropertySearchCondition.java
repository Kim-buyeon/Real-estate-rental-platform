package com.duri.rentalplatform.domain.property.dto.condition;

import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PropertySortKey;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 매물 조회 조건. 서비스가 요청을 가공해 만든다 — 반경은 바운딩 박스로, 커서는 정렬 값 · 식별자로.
 *
 * <p>쓰지 않는 조회에서는 좌표 · 정렬 · 커서 · 격자 필드가 null 이다. 자치구 집계와 마커는 정렬 · 커서를,
 * 목록은 좌표를 비운다. 격자 필드는 지도 묶음만 채운다.
 *
 * @param nullDebtRatio 전세가율 정렬에서 미분석 매물이 갖는 대체값. 방향과 무관하게 맨 뒤로 가도록
 *                      서비스가 고른다
 * @param limit         조회 건수. 목록은 요청 크기 + 1, 그 외는 null(제한 없음)
 * @param cellLat       지도 묶음의 칸 높이(위도). 지도 묶음 외 조회는 null
 * @param cellLng       지도 묶음의 칸 너비(경도). 지도 묶음 외 조회는 null
 * @param maxCellIndex  지도 묶음의 마지막 칸 번호(칸 수 − 1). 지도 묶음 외 조회는 null
 */
public record PropertySearchCondition(
        String district,
        ContractType contractType,
        Long depositMin,
        Long depositMax,
        Long monthlyRentMax,
        PropertyType propertyType,
        List<RiskGrade> riskGrades,
        BigDecimal areaMin,
        BigDecimal areaMax,
        Double minLat,
        Double maxLat,
        Double minLng,
        Double maxLng,
        PropertySortKey sortKey,
        boolean ascending,
        BigDecimal nullDebtRatio,
        Long lastDeposit,
        BigDecimal lastDebtRatio,
        LocalDateTime lastRegisteredAt,
        Long lastId,
        Integer limit,
        Double cellLat,
        Double cellLng,
        Integer maxCellIndex
) {

    /** 공통 필터만 있는 조건 — 자치구 집계. */
    public static PropertySearchCondition ofFilter(DistrictCountRequest f) {
        return new PropertySearchCondition(f.district(), f.contractType(), f.depositMin(),
                f.depositMax(), f.monthlyRentMax(), f.propertyType(), f.riskGrade(), f.areaMin(),
                f.areaMax(), null, null, null, null, null, false, null, null, null, null, null, null,
                null, null, null);
    }

    /** 공통 필터 + 바운딩 박스 — 마커. */
    public static PropertySearchCondition ofMarkers(DistrictCountRequest f, BoundingBox box) {
        return new PropertySearchCondition(f.district(), f.contractType(), f.depositMin(),
                f.depositMax(), f.monthlyRentMax(), f.propertyType(), f.riskGrade(), f.areaMin(),
                f.areaMax(), box.minLat(), box.maxLat(), box.minLng(), box.maxLng(),
                null, false, null, null, null, null, null, null, null, null, null);
    }

    /** 공통 필터 + 정렬 · 키셋 — 목록. */
    public static PropertySearchCondition ofList(DistrictCountRequest f, PropertySortKey sortKey,
            boolean ascending, BigDecimal nullDebtRatio, Long lastDeposit, BigDecimal lastDebtRatio,
            LocalDateTime lastRegisteredAt, Long lastId, int limit) {
        return new PropertySearchCondition(f.district(), f.contractType(), f.depositMin(),
                f.depositMax(), f.monthlyRentMax(), f.propertyType(), f.riskGrade(), f.areaMin(),
                f.areaMax(), null, null, null, null, sortKey, ascending, nullDebtRatio, lastDeposit,
                lastDebtRatio, lastRegisteredAt, lastId, limit, null, null, null);
    }

    /** 공통 필터 + 바운딩 박스 + 격자 칸 크기 — 지도 묶음(명세 1.12). */
    public static PropertySearchCondition ofClusters(DistrictCountRequest f, BoundingBox box,
            double cellLat, double cellLng, int maxCellIndex) {
        return new PropertySearchCondition(f.district(), f.contractType(), f.depositMin(),
                f.depositMax(), f.monthlyRentMax(), f.propertyType(), f.riskGrade(), f.areaMin(),
                f.areaMax(), box.minLat(), box.maxLat(), box.minLng(), box.maxLng(),
                null, false, null, null, null, null, null, null, cellLat, cellLng, maxCellIndex);
    }
}
