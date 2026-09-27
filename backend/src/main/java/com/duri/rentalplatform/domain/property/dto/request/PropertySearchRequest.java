package com.duri.rentalplatform.domain.property.dto.request;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

/**
 * 매물 조회 요청. API 명세서(매물) 1.1 공통 필터 + 1.3 반경 조건 · 목록 파라미터.
 *
 * <p>반경 조건(중심 · 반경 세 값)이 있으면 마커, 없으면 목록이다. 판정은 서비스가 한다.
 *
 * <p>표시 영역 네 값({@code minLat} 등)은 명세 1.3이 거부하는 파라미터다. 받아서 서비스가 INVALID_REQUEST로
 * 거부하도록 필드를 둔다 — 자치구 단계의 표시 영역 조회는 1.12 묶음 조회의 몫이다.
 *
 * <p>{@code radiusKm}은 0 초과 2 이하(명세 1.3). {@code size} 하한은 명세에 없다. 0 이하가 SQL 오류(500)로
 * 새지 않게 막는다 — 명세 보완 대상.
 */
public record PropertySearchRequest(
        String district,
        ContractType contractType,
        Long depositMin,
        Long depositMax,
        Long monthlyRentMax,
        PropertyType propertyType,
        List<RiskGrade> riskGrade,
        BigDecimal areaMin,
        BigDecimal areaMax,
        Double minLat,
        Double maxLat,
        Double minLng,
        Double maxLng,
        Double lat,
        Double lng,
        @Positive @DecimalMax("2.0") Double radiusKm,
        String sort,
        String cursor,
        @Min(1) @Max(PropertySearchRequest.MAX_SIZE) Integer size
) {

    /** API 명세서 공통 규약 1.4. */
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PropertySearchRequest {
        if (size == null) {
            size = DEFAULT_SIZE;
        }
    }

    /** 공통 검색 필터 부분. */
    public DistrictCountRequest toFilter() {
        return new DistrictCountRequest(district, contractType, depositMin, depositMax,
                monthlyRentMax, propertyType, riskGrade, areaMin, areaMax);
    }
}
