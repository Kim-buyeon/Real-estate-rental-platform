package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.BuildingRegistry;
import com.duri.rentalplatform.domain.risk.entity.MortgageHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 을구 저장. */
public interface MortgageHistoryRepository extends JpaRepository<MortgageHistory, Long> {

    /** 표제부의 을구 전체. 위험도 분석 입력이다. */
    List<MortgageHistory> findByRegistry(BuildingRegistry registry);
}
