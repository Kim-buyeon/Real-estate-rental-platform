package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.GuaranteePremiumRate;
import org.springframework.data.jpa.repository.JpaRepository;

/** 보증료율 조회. */
public interface GuaranteePremiumRateRepository extends JpaRepository<GuaranteePremiumRate, Long> {
}
