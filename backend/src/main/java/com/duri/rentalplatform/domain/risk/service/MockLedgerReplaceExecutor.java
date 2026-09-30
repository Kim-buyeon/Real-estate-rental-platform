package com.duri.rentalplatform.domain.risk.service;

import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import com.duri.rentalplatform.domain.property.enums.LedgerReplacementOutcome;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.service.LedgerCommandService;
import com.duri.rentalplatform.domain.property.vo.LedgerReplacement;
import com.duri.rentalplatform.domain.risk.repository.RiskAnalysisRepository;
import com.duri.rentalplatform.domain.risk.vo.MockLedgerReplaceAttempt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mock 대장 교체 배치의 매물 단위 처리. 매물 분석 락 안에서 대장을 건축HUB 로 다시 떼어 Mock 행을 바꾸거나 지우고, 재분석한다.
 *
 * <p><b>왜 있는가</b> — 대장 수집은 「없을 때만」이라 연동을 real 로 바꿔도 이미 저장된 Mock 대장이 남는다. real 연동에서
 * 판정은 Mock 대장을 「대장 없음」으로 보지만({@link RiskAnalysisCommandService}), 실데이터로 바꿔야 대장 항목이 확인된다.
 *
 * <p><b>결과별 처리</b>
 * <ul>
 *   <li>뗐다 — 같은 행을 건축HUB 대장으로 바꾼다. 행 식별자가 유지되어 분석 이력의 참조가 이어진다.</li>
 *   <li>뗄 대장이 없다 — Mock 행을 지운다. 분석 행(이력 포함)의 대장 참조를 먼저 NULL 로 끊는다({@code risk_analysis.ledger_id}
 *       외래 키, V17 부터 NULL 허용). 둘은 한 트랜잭션이다. 대장 항목은 확인 불가가 되고, 뒤 조회가 다시 수집을 시도한다.</li>
 *   <li>일일 상한 — 손대지 않는다. 배치는 그날 멈춘다.</li>
 *   <li>이미 Mock 이 아니다 — 손대지 않는다.</li>
 * </ul>
 * 바꾸거나 지웠으면 재분석한다 — 저장된 분석이 Mock 대장으로 판정된 것이라서다. 재분석은 대장 수집을 건너뛴다
 * ({@link RiskAnalysisCommandService#analyzeWithCollectedLedger}) — 지운 매물의 대장을 다시 떼면 상한을 한 번 더 쓴다. 판정
 * 결론이 같으면 새 분석 행을 남기지 않는다.
 *
 * <p><b>별도 빈 · 락</b> — {@link PropertyRefreshAnalysisExecutor} 와 같다. 락은 프록시 기반 관점이 걸고, 사용자 재분석 · 두
 * 갱신 배치와 같은 키 {@code risk:analysis:lock:{propertyId}} 를 기다리지 않고 한 번만 시도한다. 못 잡으면
 * {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} 이 나고 처리 단계가 건너뜀으로 바꾼다.
 *
 * <p><b>실패</b> — 락 안의 예외는 잡아 결과에 담는다. 연동 장애(서킷 열림 포함)면 Mock 행은 그대로 남아 다음 회차에 다시 본다.
 *
 * <p><b>트랜잭션</b> — 이 메서드는 열지 않는다. 대장 떼기는 외부 호출이다. 삭제만 여기서 경계를 긋고, 교체 · 재분석은 각
 * 서비스가 긋는다.
 */
@Service
public class MockLedgerReplaceExecutor {

    private final LedgerCommandService ledgerCommandService;
    private final RiskAnalysisCommandService riskAnalysisCommandService;
    private final BuildingLedgerRepository buildingLedgerRepository;
    private final RiskAnalysisRepository riskAnalysisRepository;
    private final TransactionTemplate writeTransaction;

    public MockLedgerReplaceExecutor(
            LedgerCommandService ledgerCommandService,
            RiskAnalysisCommandService riskAnalysisCommandService,
            BuildingLedgerRepository buildingLedgerRepository,
            RiskAnalysisRepository riskAnalysisRepository,
            PlatformTransactionManager transactionManager) {
        this.ledgerCommandService = ledgerCommandService;
        this.riskAnalysisCommandService = riskAnalysisCommandService;
        this.buildingLedgerRepository = buildingLedgerRepository;
        this.riskAnalysisRepository = riskAnalysisRepository;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 매물의 Mock 대장을 교체하거나 지우고 재분석한다.
     *
     * @return 처리 결과. 연동 · 저장 · 재분석의 실패도 여기에 담긴다
     * @throws com.duri.rentalplatform.common.BusinessException {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 매물 락을 못
     *                                                          잡았을 때
     */
    @DistributedLock(
            key = "'risk:analysis:lock:' + #propertyId",
            waitTimeout = "0s",
            leaseTime = "${risk.reanalyze.lock-lease-time}")
    public MockLedgerReplaceAttempt replaceAndAnalyze(Long propertyId) {
        // 교체 · 삭제를 저장까지 마친 결과. 그 전에 실패하면 null 이라 집계에서 교체 · 삭제로 세지 않는다.
        LedgerReplacementOutcome applied = null;
        try {
            LedgerReplacement replacement = ledgerCommandService.fetchMockReplacement(propertyId);
            switch (replacement.outcome()) {
                case NOT_MOCK, QUOTA_EXHAUSTED -> {
                    return MockLedgerReplaceAttempt.completed(replacement.outcome(), false);
                }
                case FETCHED -> ledgerCommandService.replaceMock(propertyId, replacement.document());
                case NOT_FOUND -> removeMock(propertyId);
            }
            applied = replacement.outcome();
            riskAnalysisCommandService.analyzeWithCollectedLedger(propertyId);
            return MockLedgerReplaceAttempt.completed(applied, true);
        } catch (RuntimeException e) {
            return MockLedgerReplaceAttempt.failed(applied, e);
        }
    }

    /** 분석 행의 참조를 끊고 Mock 대장 행을 지운다. 그 사이 다른 경로가 지웠거나 Mock 이 아니게 됐으면 손대지 않는다. */
    private void removeMock(Long propertyId) {
        writeTransaction.executeWithoutResult(status -> buildingLedgerRepository.findByPropertyId(propertyId)
                .filter(BuildingLedger::isMock)
                .ifPresent(ledger -> {
                    riskAnalysisRepository.detachLedger(ledger.getLedgerId());
                    buildingLedgerRepository.delete(ledger);
                }));
    }
}
