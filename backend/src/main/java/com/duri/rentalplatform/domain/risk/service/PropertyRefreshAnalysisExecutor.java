package com.duri.rentalplatform.domain.risk.service;

import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.property.service.PropertyLoadWriter;
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
 * 막힌다. <b>이전 등급 보존과 등급 변동 알림은 판정이 갖는다</b> — {@link RiskAnalysisCommandService} 가 새 최신 행에 직전
 * 등급을 남기고, 등급이 바뀌었으면 이벤트를 발행하며, 알림 생성은 그 이벤트를 받아 관심 매물 등록자에게만 만든다.
 *
 * <p><b>갈래마다 부르는 판정이 다르다</b> — 시세 변경 재분석은 대장도 다시 떼지 않는다
 * ({@link RiskAnalysisCommandService#analyzeWithCollectedLedger}). 시세는 대장과 무관하고, 떼면 대장 없는 매물마다 건축HUB
 * 초당 한도를 기다리게 된다(#328). 첫 판정만 등기 · 대장을 수집한다({@link RiskAnalysisCommandService#analyze}). 두 메서드는
 * 판정 저장을 공유하므로 이전 등급 보존과 이벤트는 같다 — 직전 판정이 없는 첫 판정에서는 이벤트가 나지 않는다. 재분석 대기
 * 표시는 있으나 판정 이력이 없는 매물은 대장 없이 판정되며, 그 대장은 Mock 대장 교체 배치(대장 행 없음 · 조회 키 있음)가 채운다.
 *
 * <p><b>락</b> — 사용자 재분석 · 등기 재조회 배치와 같은 키 {@code risk:analysis:lock:{propertyId}} 를 기다리지 않고 한 번만
 * 시도한다. 못 잡으면 다른 쪽이 같은 매물을 이미 판정하고 있으므로 건너뛴다. 이때 관점이
 * {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} 를 던진다.
 *
 * <p><b>실패</b> — 락 안의 예외는 잡아 결과에 담는다. 그래서 이 메서드 밖으로 나오는 예외는 락 획득 단계의 것뿐이다.
 *
 * <p><b>재분석 대기 표시</b> — 시세 변경 재분석은 판정을 마치면 매물의 재분석 대기 표시(V18)를 내린다. 판정 단계는 이 표시로
 * 대상을 DB 에서 읽으므로, 내리지 않으면 결론이 같아 판정 행이 늘지 않는 매물까지 매 회차 다시 나온다. 판정이 실패하면 내리지 않아
 * 다음 회차가 다시 잡는다. 내리는 호출은 판정 저장과 별도 트랜잭션이다 — 실패하면 다음 회차에 한 번 더 판정할 뿐이다.
 *
 * <p><b>트랜잭션</b> — 열지 않는다. 두 갈래 모두 외부 호출을 포함할 수 있다 — 첫 판정은 등기 · 대장 수집을, 시세 변경 재분석은
 * 등기가 없을 때 등기 수집을. 판정 서비스 · 적재 쓰기 서비스가 각자 경계를 긋는다.
 */
@Service
public class PropertyRefreshAnalysisExecutor {

    private final RiskAnalysisCommandService riskAnalysisCommandService;
    private final PropertyLoadWriter propertyLoadWriter;

    public PropertyRefreshAnalysisExecutor(RiskAnalysisCommandService riskAnalysisCommandService,
                                           PropertyLoadWriter propertyLoadWriter) {
        this.riskAnalysisCommandService = riskAnalysisCommandService;
        this.propertyLoadWriter = propertyLoadWriter;
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
            if (target.priceChanged()) {
                riskAnalysisCommandService.analyzeWithCollectedLedger(target.propertyId());
                propertyLoadWriter.completeReanalysis(target.propertyId());
            } else {
                riskAnalysisCommandService.analyze(target.propertyId());
            }
            return PropertyRefreshAttempt.analyzed(target);
        } catch (RuntimeException e) {
            return PropertyRefreshAttempt.failed(target, e);
        }
    }
}
