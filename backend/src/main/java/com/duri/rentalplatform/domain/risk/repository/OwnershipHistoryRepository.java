package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.OwnershipHistory;
import org.springframework.data.jpa.repository.JpaRepository;

/** 갑구 저장. */
public interface OwnershipHistoryRepository extends JpaRepository<OwnershipHistory, Long> {
}
