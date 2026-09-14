package com.duri.rentalplatform.domain.risk.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.store.ReanalyzeIntervalStore;
import org.springframework.stereotype.Service;

/**
 * 매물 분산 락 안에서 도는 재분석 본체(RISK-08). API 명세서(위험도 분석) 1.2.
 *
 * <p><b>별도 빈인 이유</b> — 락은 프록시 기반 관점이 건다. {@link RiskReanalysisCommandService} 안의 메서드로 두면 같은 클래스
 * 호출이라 프록시를 거치지 않아 락이 걸리지 않는다 — 아키텍처 설계서(횡단 관심사) 1.2.
 *
 * <p><b>재확인</b> — 락을 잡으면 간격 키를 다시 본다. 걸려 있으면 락을 기다리는 사이 먼저 요청이 등기를 떼고 분석을 끝낸
 * 것이므로 재조회 없이 분석 결과만 돌려준다 — 「기다린 요청은 등기를 다시 떼지 않는다」. 간격은 이 메서드 안에서 걸리고 락은
 * 메서드 반환 뒤에 풀리므로, 락을 넘겨받은 요청은 반드시 간격을 본다.
 *
 * <p><b>트랜잭션</b> — 열지 않는다. 등기 재조회는 외부 호출을 포함하고, 재조회 · 분석 서비스가 각자 경계를 긋는다.
 */
@Service
public class RiskReanalysisExecutor {

    private final RegistryCommandService registryCommandService;
    private final RiskAnalysisCommandService riskAnalysisCommandService;
    private final ReanalyzeIntervalStore reanalyzeIntervalStore;

    public RiskReanalysisExecutor(
            RegistryCommandService registryCommandService,
            RiskAnalysisCommandService riskAnalysisCommandService,
            ReanalyzeIntervalStore reanalyzeIntervalStore) {
        this.registryCommandService = registryCommandService;
        this.riskAnalysisCommandService = riskAnalysisCommandService;
        this.reanalyzeIntervalStore = reanalyzeIntervalStore;
    }

    /**
     * 간격이 걸려 있지 않으면 등기를 다시 떼어 분석하고 간격을 건다. 걸려 있으면 분석만 한다.
     *
     * @throws BusinessException {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 등기 · 대장 수집이 실패했거나 락 대기 상한을
     *                           넘었을 때. 실패하면 간격을 걸지 않는다
     */
    @DistributedLock(
            key = "'risk:analysis:lock:' + #propertyId",
            waitTimeout = "${risk.reanalyze.lock-wait-timeout}",
            pollInterval = "${risk.reanalyze.lock-poll-interval}",
            leaseTime = "${risk.reanalyze.lock-lease-time}")
    public RiskResponse refreshAndAnalyze(Long propertyId) {
        if (reanalyzeIntervalStore.remaining(propertyId).isPresent()) {
            return riskAnalysisCommandService.analyze(propertyId);
        }
        registryCommandService.refresh(propertyId);
        RiskResponse analyzed = riskAnalysisCommandService.analyze(propertyId);
        reanalyzeIntervalStore.markAnalyzed(propertyId);
        return analyzed;
    }
}
