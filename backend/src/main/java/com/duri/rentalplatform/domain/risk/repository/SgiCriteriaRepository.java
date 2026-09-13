package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.SgiCriteria;
import org.springframework.data.jpa.repository.JpaRepository;

/** SGI 세부 기준 조회. */
public interface SgiCriteriaRepository extends JpaRepository<SgiCriteria, Long> {
}
