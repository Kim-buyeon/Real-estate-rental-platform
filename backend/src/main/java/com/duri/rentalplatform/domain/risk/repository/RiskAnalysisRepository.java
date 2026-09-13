package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.RiskAnalysis;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 위험도 분석 이력 저장. 목록 · 지도의 최신 등급 조회는 매물 매퍼가 맡는다. */
public interface RiskAnalysisRepository extends JpaRepository<RiskAnalysis, Long> {

    /** 매물의 최신 분석. */
    Optional<RiskAnalysis> findByPropertyIdAndLatestTrue(Long propertyId);
}
