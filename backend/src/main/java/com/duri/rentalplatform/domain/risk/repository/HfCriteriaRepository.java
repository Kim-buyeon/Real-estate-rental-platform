package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.HfCriteria;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** HF 세부 기준 조회. */
public interface HfCriteriaRepository extends JpaRepository<HfCriteria, Long> {

    Optional<HfCriteria> findByGuaranteeId(Long guaranteeId);
}
