package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.InsuranceProduct;
import org.springframework.data.jpa.repository.JpaRepository;

/** 보증 상품 조회. */
public interface InsuranceProductRepository extends JpaRepository<InsuranceProduct, Long> {
}
