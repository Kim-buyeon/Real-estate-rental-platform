package com.duri.rentalplatform.domain.property.repository;

import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 건축물대장 저장. 화면 조회는 매퍼가 맡는다 — 아키텍처 설계서(영속성 구조) 1.1. */
public interface BuildingLedgerRepository extends JpaRepository<BuildingLedger, Long> {

    /** 이 매물의 대장을 이미 수집했는가. */
    boolean existsByPropertyId(Long propertyId);

    /** 매물의 대장. 위험도 분석 입력이다. */
    Optional<BuildingLedger> findByPropertyId(Long propertyId);
}
