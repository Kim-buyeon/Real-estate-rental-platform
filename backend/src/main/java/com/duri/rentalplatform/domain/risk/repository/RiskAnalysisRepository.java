package com.duri.rentalplatform.domain.risk.repository;

import com.duri.rentalplatform.domain.risk.entity.RiskAnalysis;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 위험도 분석 이력 저장. 목록 · 지도의 최신 등급 조회는 매물 매퍼가 맡는다. */
public interface RiskAnalysisRepository extends JpaRepository<RiskAnalysis, Long> {

    /** 매물의 최신 분석. 잠그지 않는다 — 저장된 판정 조회 · 배치의 대상 판단이 쓴다. 판정 기록은 {@link #findLatestForUpdate}. */
    Optional<RiskAnalysis> findByPropertyIdAndLatestTrue(Long propertyId);

    /**
     * 매물의 최신 분석을 행 잠금(SELECT … FOR UPDATE)으로 읽는다 — 판정 기록(RiskAnalysisCommandService.record)만 쓴다. 같은 매물의
     * 판정 기록 둘을 이 행에서 줄 세워, 나중 쪽이 먼저 쪽이 커밋한 최신 행을 보고 결론을 비교 · 매물 비정규화 열(V22)을 쓰게 한다.
     *
     * <p>기다린 쪽의 흐름(READ COMMITTED) — 먼저 쪽이 이 행을 이력으로 내리고(is_latest = false) 새 최신 행을 넣고 커밋하면, 기다린
     * 쪽은 잠금을 얻은 뒤 행의 새 판으로 조건(is_latest)을 다시 확인해 빈 결과를 받는다. 첫 분석으로 보고 새 최신 행을 넣다가 최신 행
     * 유일 인덱스(uq_risk_analysis_latest)에 걸리고, judge() 의 기존 재시도가 새 트랜잭션에서 다시 판정한다 — 그때는 먼저 쪽의 최신
     * 행을 읽는다. 먼저 쪽이 결론이 같아 행을 내리지 않았으면 기다린 쪽은 그 행을 그대로 받는다.
     *
     * <p>최신 행이 아직 없으면(첫 분석) 잠글 행이 없다 — 그 경합은 지금처럼 유일 인덱스와 재시도가 맡는다. 잠금 대기는 앱 커넥션의
     * statement_timeout(30s, #405)에 포함된다. 판정 기록 트랜잭션은 외부 수집을 밖에 두고 입력 읽기 · 판정 · 저장만 하므로 짧다 —
     * 대기가 그 상한에 닿으면 취소(57014)되어 503 으로 나간다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RiskAnalysis r WHERE r.propertyId = :propertyId AND r.latest = true")
    Optional<RiskAnalysis> findLatestForUpdate(@Param("propertyId") Long propertyId);

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
