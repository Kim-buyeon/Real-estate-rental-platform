package com.duri.rentalplatform.domain.risk.service;

import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.risk.entity.BuildingRegistry;
import com.duri.rentalplatform.domain.risk.entity.RiskAnalysis;
import com.duri.rentalplatform.domain.risk.enums.RegistryRefreshOutcome;
import com.duri.rentalplatform.domain.risk.repository.BuildingRegistryRepository;
import com.duri.rentalplatform.domain.risk.repository.RiskAnalysisRepository;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshAttempt;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 등기 재조회 배치(RISK-08)의 매물 단위 처리. 매물 분석 락 안에서 등기를 다시 떼고, 필요하면 재분석한다.
 *
 * <p><b>별도 빈인 이유</b> — 락은 프록시 기반 관점이 건다. 배치 스텝의 처리 단계는 빈이 아니고, 같은 클래스 안 호출로 두면 락이
 * 걸리지 않는다 — 아키텍처 설계서(횡단 관심사) 1.2. 사용자 재분석의 {@link RiskReanalysisExecutor} 와 나눈 이유는 락 안에서 하는
 * 일이 달라서다 — 배치는 간격을 보지도 걸지도 않고, 재분석이 필요할 때만 분석한다.
 *
 * <p><b>재분석 조건</b> — 셋 중 하나면 분석한다.
 * <ol>
 *   <li>이번 재조회로 등기가 바뀌었다(이력 교체 · 첫 수집)</li>
 *   <li>최신 분석이 없다</li>
 *   <li>저장된 등기의 수집 시각({@code building_registry.updated_at})이 최신 분석 시각({@code risk_analysis.analyzed_at})보다
 *       늦다 — 전날 변동을 반영한 뒤 분석이 실패했으면 오늘 재조회는 「그대로」라 1 만으로는 영영 다시 분석되지 않는다</li>
 * </ol>
 * 3 은 분석 결론이 같아 새 행을 남기지 않은 매물에서도 참으로 남는다 — 분석은 결론이 바뀔 때만 행을 남기기 때문이다. 그런 매물은
 * 회차마다 다시 분석되지만, 등기 · 대장은 이미 있어 외부 호출 없이 DB 판정만 한다.
 *
 * <p><b>락</b> — 사용자 재분석과 같은 키 {@code risk:analysis:lock:{propertyId}} 를 기다리지 않고 한 번만 시도한다. 못 잡으면
 * 사용자 요청이 이미 같은 매물의 등기를 떼고 있으므로 이번 회차는 건너뛴다. 이때 관점이
 * {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} 를 던진다.
 *
 * <p><b>실패</b> — 락 안의 예외는 잡아 {@link RegistryRefreshAttempt} 에 담는다. 그래서 이 메서드 밖으로 나오는 업무 예외는 락을
 * 못 잡은 경우뿐이다. 간격을 걸지 않으므로 배치 직후에도 사용자는 재분석을 요청할 수 있다.
 *
 * <p><b>트랜잭션</b> — 열지 않는다. 재조회는 외부 호출을 포함하고, 재조회 · 분석 서비스가 각자 경계를 긋는다.
 */
@Service
public class RegistryRefreshBatchExecutor {

    private final RegistryCommandService registryCommandService;
    private final RiskAnalysisCommandService riskAnalysisCommandService;
    private final BuildingRegistryRepository buildingRegistryRepository;
    private final RiskAnalysisRepository riskAnalysisRepository;

    public RegistryRefreshBatchExecutor(
            RegistryCommandService registryCommandService,
            RiskAnalysisCommandService riskAnalysisCommandService,
            BuildingRegistryRepository buildingRegistryRepository,
            RiskAnalysisRepository riskAnalysisRepository) {
        this.registryCommandService = registryCommandService;
        this.riskAnalysisCommandService = riskAnalysisCommandService;
        this.buildingRegistryRepository = buildingRegistryRepository;
        this.riskAnalysisRepository = riskAnalysisRepository;
    }

    /**
     * 등기를 다시 떼고, 등기가 바뀌었거나 분석이 등기보다 뒤처져 있으면 위험도를 재분석한다.
     *
     * @return 처리 결과. 재조회 · 재분석의 실패도 여기에 담긴다
     * @throws com.duri.rentalplatform.common.BusinessException {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 매물 락을 못
     *                                                          잡았을 때
     */
    @DistributedLock(
            key = "'risk:analysis:lock:' + #propertyId",
            waitTimeout = "0s",
            leaseTime = "${risk.reanalyze.lock-lease-time}")
    public RegistryRefreshAttempt refreshAndAnalyzeIfNeeded(Long propertyId) {
        RegistryRefreshOutcome outcome = null;
        try {
            outcome = registryCommandService.refresh(propertyId);
            if (!outcome.isModified() && !analysisBehindRegistry(propertyId)) {
                return RegistryRefreshAttempt.completed(outcome, false);
            }
            riskAnalysisCommandService.analyze(propertyId);
            return RegistryRefreshAttempt.completed(outcome, true);
        } catch (RuntimeException e) {
            return RegistryRefreshAttempt.failed(outcome, e);
        }
    }

    /** 최신 분석이 없거나, 저장된 등기가 최신 분석 뒤에 수집됐는가. 같은 시각은 뒤처진 것으로 보지 않는다. */
    private boolean analysisBehindRegistry(Long propertyId) {
        Optional<RiskAnalysis> latest = riskAnalysisRepository.findByPropertyIdAndLatestTrue(propertyId);
        if (latest.isEmpty()) {
            return true;
        }
        // 재조회 직후라 등기가 없으면 수집 경로의 결함이다. 분석에 넘겨 그쪽의 오류로 드러나게 한다.
        Optional<BuildingRegistry> registry = buildingRegistryRepository.findByPropertyId(propertyId);
        return registry.isEmpty() || registry.get().getUpdatedAt().isAfter(latest.get().getCreatedAt());
    }
}
