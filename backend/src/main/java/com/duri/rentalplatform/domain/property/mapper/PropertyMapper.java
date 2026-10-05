package com.duri.rentalplatform.domain.property.mapper;

import com.duri.rentalplatform.domain.property.dto.condition.ReanalysisPendingPropertyCondition;
import com.duri.rentalplatform.domain.property.dto.condition.PropertyDetailCondition;
import com.duri.rentalplatform.domain.property.dto.condition.PropertyIdsCondition;
import com.duri.rentalplatform.domain.property.dto.condition.PropertySearchCondition;
import com.duri.rentalplatform.domain.property.dto.condition.UnanalyzedPropertyCondition;
import com.duri.rentalplatform.domain.property.dto.response.PropertyListResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkerResponse;
import com.duri.rentalplatform.domain.property.vo.DistrictCountRow;
import com.duri.rentalplatform.domain.property.vo.MapClusterCellRow;
import com.duri.rentalplatform.domain.property.vo.MarkerCandidateRow;
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
     * 조건의 {@code cellLat} · {@code cellLng} · {@code maxRowIndex} · {@code maxColIndex} 가 채워져 있어야 한다.
     */
    List<MapClusterCellRow> selectClusterCells(PropertySearchCondition condition);

    /**
     * 반경 조회의 후보 — 공통 필터 · 바운딩 박스(경계 포함)를 적용한 매물의 식별자 · 좌표. 순서는 정하지 않는다 — 서비스가 거리순으로
     * 자른 뒤 {@link #selectMarkersByIds} 로 마커 컬럼을 읽는다.
     */
    List<MarkerCandidateRow> selectMarkerCandidates(PropertySearchCondition condition);

    /** 식별자 목록의 마커. 식별자 순. 목록이 비어 있으면 호출하지 않는다. */
    List<PropertyMarkerResponse> selectMarkersByIds(PropertyIdsCondition condition);

    /** 정렬 기준 + 식별자 키셋으로 {@code limit} 건. */
    List<PropertyListResponse> selectList(PropertySearchCondition condition);

    /**
     * 자치구 없는 전세가율순 목록의 앞부분 — 최신 판정의 전세가율이 대체값보다 앞에 오는(오름 &lt; {@code nullDebtRatio},
     * 내림 &gt; {@code nullDebtRatio}) 매물만, 전세가율 + 식별자 키셋으로 {@code limit} 건. 매물 전세가율 열의 부분 인덱스(V24)에서 출발한다.
     * 자치구 · 매물 조건 필터가 없을 때만 쓴다(등급 필터는 쓴다). 커서는 앞부분 안일 때만 넘긴다. 같은 구간에서 결과 · 순서가
     * {@link #selectList} 와 같고, 나머지(끝부분)는 서비스가 {@link #selectList} 로 이어 읽는다.
     */
    List<PropertyListResponse> selectListByLeaseRatioIndex(PropertySearchCondition condition);

    /** 매물 상세. 없으면 null. */
    PropertyDetailRow selectDetail(PropertyDetailCondition condition);

    /**
     * 최신 판정(is_latest)이 없는 매물 식별자를 식별자 오름차순으로 {@code limit} 건. 매물 갱신 배치(RISK-08)의 판정 대상이다.
     * 재분석 대기 표시와 무관하다 — 표시가 있어도 최신 판정이 없으면 여기서 나온다(#338). 식별자 자체가 커서라 동률이 없다.
     */
    List<Long> selectUnanalyzedPropertyIds(UnanalyzedPropertyCondition condition);

    /**
     * 판정 입력(시세 · 등기 · 대장)이 바뀌어 재분석을 기다리는 매물(is_reanalysis_pending) 중 최신 판정이 있는 매물의 식별자를 식별자 오름차순으로
     * {@code limit} 건. 매물 갱신 배치(RISK-08)의 재분석 대상이다. 최신 판정이 없는 대기 매물은
     * {@link #selectUnanalyzedPropertyIds} 가 내주므로 두 조회가 겹치지 않는다. 식별자 자체가 커서라 동률이 없다.
     */
    List<Long> selectReanalysisPendingPropertyIds(ReanalysisPendingPropertyCondition condition);
}
