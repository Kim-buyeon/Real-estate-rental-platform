package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.RiskCriteria;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 위험 등급 기준 조회. 단일 행이다. */
public interface RiskCriteriaRepository extends JpaRepository<RiskCriteria, Long> {

    Optional<RiskCriteria> findFirstByOrderByRiskCriteriaIdAsc();
}
