package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.GuaranteeCriteria;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 보증기관 공통 기준 조회. 판정은 읽기만 한다. */
public interface GuaranteeCriteriaRepository extends JpaRepository<GuaranteeCriteria, Long> {

    Optional<GuaranteeCriteria> findByProvider(GuaranteeProvider provider);
}
