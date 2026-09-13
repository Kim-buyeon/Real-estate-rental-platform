package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.MortgageHistory;
import org.springframework.data.jpa.repository.JpaRepository;

/** 을구 저장. */
public interface MortgageHistoryRepository extends JpaRepository<MortgageHistory, Long> {
}
