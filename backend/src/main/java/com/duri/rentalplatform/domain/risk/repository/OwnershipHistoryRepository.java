package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.BuildingRegistry;
import com.duri.rentalplatform.domain.risk.entity.OwnershipHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 갑구 저장. */
public interface OwnershipHistoryRepository extends JpaRepository<OwnershipHistory, Long> {

    /** 표제부의 갑구 전체. 위험도 분석 입력이다. */
    List<OwnershipHistory> findByRegistry(BuildingRegistry registry);
}
