package com.duri.rentalplatform.domain.loan.repository;

import com.duri.rentalplatform.domain.loan.entity.LoanProduct;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 대출 상품 조회. 1단계는 대표 상품 한 행(시드 예시값)을 쓴다. 상품 선택 · 추천은 LOAN-03 차기. */
public interface LoanProductRepository extends JpaRepository<LoanProduct, Long> {

    Optional<LoanProduct> findFirstByOrderByLoanIdAsc();
}
