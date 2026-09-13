package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.BuildingRegistry;
import org.springframework.data.jpa.repository.JpaRepository;

/** 표제부 저장. 등기 이력 화면 조회는 매퍼가 맡는다 — 아키텍처 설계서(영속성 구조) 1.1. */
public interface BuildingRegistryRepository extends JpaRepository<BuildingRegistry, Long> {

    /** 이 매물의 등기를 이미 수집했는가. */
    boolean existsByPropertyId(Long propertyId);
}
