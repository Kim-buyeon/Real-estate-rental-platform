package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.RiskAnalysis;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 위험도 분석 이력 저장. 목록 · 지도의 최신 등급 조회는 매물 매퍼가 맡는다. */
public interface RiskAnalysisRepository extends JpaRepository<RiskAnalysis, Long> {

    /** 매물의 최신 분석. */
    Optional<RiskAnalysis> findByPropertyIdAndLatestTrue(Long propertyId);

    /**
     * 대장을 가리키는 분석 행(이력 포함)의 참조를 끊는다 — 대장 행을 지우기 전에. {@code risk_analysis.ledger_id} 는 대장을
     * 참조하는 외래 키라 먼저 끊지 않으면 삭제가 막힌다. NULL 은 「대장 없이 분석함」이다(V17). 바꾼 행 수.
     *
     * <p>영속성 컨텍스트를 우회한다. 같은 트랜잭션에서 이미 읽은 분석 엔티티의 값은 갱신되지 않는다.
     */
    @Modifying
    @Query("UPDATE RiskAnalysis r SET r.ledgerId = NULL WHERE r.ledgerId = :ledgerId")
    int detachLedger(@Param("ledgerId") Long ledgerId);
}
