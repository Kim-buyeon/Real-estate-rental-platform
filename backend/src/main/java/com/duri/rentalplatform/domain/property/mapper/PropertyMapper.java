package com.duri.rentalplatform.domain.property.mapper;

import com.duri.rentalplatform.domain.property.dto.condition.PropertyDetailCondition;
import com.duri.rentalplatform.domain.property.dto.condition.PropertyIdsCondition;
import com.duri.rentalplatform.domain.property.dto.condition.PropertySearchCondition;
import com.duri.rentalplatform.domain.property.dto.condition.UnanalyzedPropertyCondition;
import com.duri.rentalplatform.domain.property.dto.response.PropertyListResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkerResponse;
import com.duri.rentalplatform.domain.property.vo.DistrictCountRow;
import com.duri.rentalplatform.domain.property.vo.MapClusterCellRow;
import com.duri.rentalplatform.domain.property.vo.PropertyDetailRow;
import java.util.List;

/** 매물 조회. XML 은 {@code resources/mapper/property/PropertyMapper.xml}. */
public interface PropertyMapper {

    /** 공통 필터를 적용한 자치구별 매물 수 · 등급 분포. 자치구명 순. */
    List<DistrictCountRow> selectDistrictCounts(PropertySearchCondition condition);

    /** 바운딩 박스 안의 마커. 경계 포함. 식별자 순. */
    List<PropertyMarkerResponse> selectMarkers(PropertySearchCondition condition);

    /**
     * 공통 필터 · 바운딩 박스를 적용한 매물을 격자 칸으로 묶은 집계(명세 1.12). 매물이 있는 칸만, 행 · 열 순.
     * 조건의 {@code cellLat} · {@code cellLng} · {@code maxCellIndex} 가 채워져 있어야 한다.
     */
    List<MapClusterCellRow> selectClusterCells(PropertySearchCondition condition);

    /** 식별자 목록의 마커. 식별자 순. 목록이 비어 있으면 호출하지 않는다. */
    List<PropertyMarkerResponse> selectMarkersByIds(PropertyIdsCondition condition);

    /** 정렬 기준 + 식별자 키셋으로 {@code limit} 건. */
    List<PropertyListResponse> selectList(PropertySearchCondition condition);

    /** 매물 상세. 없으면 null. */
    PropertyDetailRow selectDetail(PropertyDetailCondition condition);

    /**
     * 최신 판정(is_latest)이 없는 매물 식별자를 식별자 오름차순으로 {@code limit} 건. 매물 갱신 배치(RISK-08)의 판정 대상이다.
     * 식별자 자체가 커서라 동률이 없다.
     */
    List<Long> selectUnanalyzedPropertyIds(UnanalyzedPropertyCondition condition);
}
