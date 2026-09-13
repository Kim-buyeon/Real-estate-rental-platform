package com.duri.rentalplatform.domain.risk.entity;

import com.duri.rentalplatform.common.BaseEntity;
import com.duri.rentalplatform.domain.risk.enums.RegistryDataSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 등기부등본 표제부. 매물과 1:1 이며 {@code property_id} 에 UNIQUE 제약이 있다.
 *
 * <p><b>감사 상위 클래스</b> — {@code building_registry} 에는 {@code updated_at} 이 있으므로
 * {@link BaseEntity} 를 상속한다. V1 에는 생성일시 컬럼이 없어 이 규칙을 따를 수 없었고, V4 가
 * {@code created_at} 을 더했다. 수정일시는 갑구 · 을구를 다시 떼어 반영한 시각이며 응답의
 * {@code collectedAt} 이다 — 감사 리스너는 최초 저장 때도 수정일시를 채운다.
 *
 * <p>매물은 연관 엔티티가 아니라 식별자로 갖는다. 다른 도메인의 엔티티를 끌어오지 않는다.
 */
@Entity
@Getter
@Table(name = "building_registry")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuildingRegistry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long registryId;

    @Column(nullable = false, unique = true)
    private Long propertyId;

    @Column(nullable = false, length = 50)
    private String buildingPurpose;

    @Column(length = 50)
    private String buildingStructure;

    /** 표제부 건물 주소. V8 이전에 수집된 행은 비어 있다. */
    @Column(length = 200)
    private String registryAddress;

    /** 표제부 전용면적(㎡). V8 이전에 수집된 행은 비어 있다. */
    @Column(precision = 7, scale = 2)
    private BigDecimal exclusiveArea;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RegistryDataSource dataSource;

    /** 떼어 온 표제부를 매물에 붙인다. */
    public static BuildingRegistry collect(
            Long propertyId, String buildingPurpose, String buildingStructure, String registryAddress,
            BigDecimal exclusiveArea, RegistryDataSource dataSource) {
        BuildingRegistry registry = new BuildingRegistry();
        registry.propertyId = propertyId;
        registry.buildingPurpose = buildingPurpose;
        registry.buildingStructure = buildingStructure;
        registry.registryAddress = registryAddress;
        registry.exclusiveArea = exclusiveArea;
        registry.dataSource = dataSource;
        return registry;
    }
}
