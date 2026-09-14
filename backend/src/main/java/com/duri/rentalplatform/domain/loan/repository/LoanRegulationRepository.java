package com.duri.rentalplatform.domain.loan.repository;

import com.duri.rentalplatform.domain.loan.entity.LoanRegulation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 대출 규제 기준 조회. 1단계는 서울 한 행이며, 시행일이 가장 늦은 행을 쓴다. */
public interface LoanRegulationRepository extends JpaRepository<LoanRegulation, Long> {

    Optional<LoanRegulation> findFirstByOrderByEffectiveDateDescRegulationIdDesc();
}
