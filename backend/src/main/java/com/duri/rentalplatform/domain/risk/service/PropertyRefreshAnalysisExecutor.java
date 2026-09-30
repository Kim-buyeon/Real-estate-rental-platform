package com.duri.rentalplatform.domain.risk.service;

import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshAttempt;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshTarget;
import org.springframework.stereotype.Service;

/**
 * 매물 갱신 배치(RISK-08) 판정 단계의 매물 단위 처리. 매물 분석 락 안에서 위험도를 판정한다.
 *
 * <p><b>별도 빈인 이유</b> — 락은 프록시 기반 관점이 건다. 배치 스텝의 처리 단계는 빈이 아니다 — 아키텍처 설계서(횡단 관심사)
 * 1.2. {@link RegistryRefreshBatchExecutor} 와 같은 이유다.
 *
 * <p><b>재분석을 {@link RiskReanalysisExecutor} 로 하지 않는 이유</b> — 그 실행기는 사용자 요청용이라 세 가지를 더 한다. 등기를
 * 다시 떼고(외부 호출), 사용자 재분석 간격을 걸고, 락을 기다린다. 시세 변경은 등기와 무관하고, 등기 변동 점검은 관심 매물만
 * 대상으로 한다 — 데이터 적재 설계서 1.5 「전체 매물을 매일 조회하지 않는다」. 간격을 걸면 배치 직후 사용자의 재분석이 429 로
 * 막힌다. 그래서 여기서는 판정만 한다. <b>이전 등급 보존과 등급 변동 알림은 판정이 갖는다</b> —
 * {@link RiskAnalysisCommandService} 가 새 최신 행에 직전 등급을 남기고, 등급이 바뀌었으면 이벤트를 발행하며, 알림 생성은 그
 * 이벤트를 받아 관심 매물 등록자에게만 만든다. 첫 판정도 같은 메서드다 — 직전 판정이 없으면 이벤트가 나지 않는다.
 *
 * <p><b>락</b> — 사용자 재분석 · 등기 재조회 배치와 같은 키 {@code risk:analysis:lock:{propertyId}} 를 기다리지 않고 한 번만
 * 시도한다. 못 잡으면 다른 쪽이 같은 매물을 이미 판정하고 있으므로 건너뛴다. 이때 관점이
 * {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} 를 던진다.
 *
 * <p><b>실패</b> — 락 안의 예외는 잡아 결과에 담는다. 그래서 이 메서드 밖으로 나오는 예외는 락 획득 단계의 것뿐이다.
 *
 * <p><b>트랜잭션</b> — 열지 않는다. 첫 판정은 등기 · 대장 수집(외부 호출)을 포함하고, 판정 서비스가 경계를 긋는다.
 */
@Service
public class PropertyRefreshAnalysisExecutor {

    private final RiskAnalysisCommandService riskAnalysisCommandService;

    public PropertyRefreshAnalysisExecutor(RiskAnalysisCommandService riskAnalysisCommandService) {
        this.riskAnalysisCommandService = riskAnalysisCommandService;
    }

    /**
     * 매물 하나를 판정한다.
     *
     * @return 처리 결과. 판정 실패도 여기에 담긴다
     * @throws com.duri.rentalplatform.common.BusinessException {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 매물 락을 못
     *                                                          잡았을 때
     */
    @DistributedLock(
            key = "'risk:analysis:lock:' + #target.propertyId()",
            waitTimeout = "0s",
            leaseTime = "${risk.reanalyze.lock-lease-time}")
    public PropertyRefreshAttempt analyze(PropertyRefreshTarget target) {
        try {
            riskAnalysisCommandService.analyze(target.propertyId());
            return PropertyRefreshAttempt.analyzed(target);
        } catch (RuntimeException e) {
            return PropertyRefreshAttempt.failed(target, e);
        }
    }
}
