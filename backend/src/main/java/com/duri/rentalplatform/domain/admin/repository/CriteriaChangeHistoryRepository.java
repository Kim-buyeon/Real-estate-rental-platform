package com.duri.rentalplatform.domain.admin.repository;

import com.duri.rentalplatform.domain.admin.entity.CriteriaChangeHistory;
import org.springframework.data.jpa.repository.JpaRepository;

/** 기준 변경 이력 기록. 조회는 매퍼가 한다. */
public interface CriteriaChangeHistoryRepository extends JpaRepository<CriteriaChangeHistory, Long> {
}
