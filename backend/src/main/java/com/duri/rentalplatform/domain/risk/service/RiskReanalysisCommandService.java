package com.duri.rentalplatform.domain.risk.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.risk.dto.response.RiskReanalyzeResponse;
import com.duri.rentalplatform.domain.risk.entity.RiskAnalysis;
import com.duri.rentalplatform.domain.risk.repository.RiskAnalysisRepository;
import com.duri.rentalplatform.domain.risk.store.ReanalyzeIntervalStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/**
 * 사용자 요청 재분석(RISK-08). API 명세서(위험도 분석) 1.2 · 1.4.
 *
 * <p><b>순서</b> — 매물 없음(404) → 최소 간격 안(429, 다음 요청 가능 시각) → 직전 등급 읽기 → 락 안의 재분석
 * ({@link RiskReanalysisExecutor}) → 응답 조립. 같은 매물을 먼저 처리 중인 요청이 있으면 실행기의 락에서 기다리고, 락을 넘겨받은
 * 뒤 간격을 재확인해 재조회 없이 분석 결과를 받는다. 대기 상한을 넘으면 503.
 *
 * <p><b>트랜잭션</b> — 이 서비스는 트랜잭션을 열지 않는다. 등기 재조회는 외부 호출을 포함하고, 재조회 · 분석 서비스가 각자 경계를
 * 긋는다. 여기서 열면 외부 호출이 트랜잭션 안에 들어가고 두 서비스의 경계가 중첩된다.
 *
 * <p><b>무상태</b> — 락과 간격은 Redis 에 둔다. 두 인스턴스가 같은 매물의 요청을 나눠 받아도 같은 락 · 같은 간격을 본다.
 */
@Service
public class RiskReanalysisCommandService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PropertyRepository propertyRepository;
    private final RiskAnalysisRepository riskAnalysisRepository;
    private final ReanalyzeIntervalStore reanalyzeIntervalStore;
    private final RiskReanalysisExecutor riskReanalysisExecutor;

    public RiskReanalysisCommandService(
            PropertyRepository propertyRepository,
            RiskAnalysisRepository riskAnalysisRepository,
            ReanalyzeIntervalStore reanalyzeIntervalStore,
            RiskReanalysisExecutor riskReanalysisExecutor) {
        this.propertyRepository = propertyRepository;
        this.riskAnalysisRepository = riskAnalysisRepository;
        this.reanalyzeIntervalStore = reanalyzeIntervalStore;
        this.riskReanalysisExecutor = riskReanalysisExecutor;
    }

    /**
     * 매물의 등기를 다시 떼어 위험도를 재분석한다.
     *
     * @throws BusinessException {@link ErrorCode#PROPERTY_NOT_FOUND} — 매물이 없을 때,
     *                           {@link ErrorCode#RISK_REANALYZE_TOO_SOON} — 최소 간격 안일 때(retryAfter 포함),
     *                           {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 등기 · 대장 수집이 실패했거나 대기 상한을 넘었을 때
     */
    public RiskReanalyzeResponse reanalyze(Long propertyId) {
        if (!propertyRepository.existsById(propertyId)) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        reanalyzeIntervalStore.remaining(propertyId).ifPresent(remaining -> {
            throw new BusinessException(ErrorCode.RISK_REANALYZE_TOO_SOON, retryAfter(remaining));
        });

        RiskGrade previousGrade = riskAnalysisRepository.findByPropertyIdAndLatestTrue(propertyId)
                .map(RiskAnalysis::getRiskGrade)
                .orElse(null);

        return RiskReanalyzeResponse.of(propertyId, previousGrade,
                riskReanalysisExecutor.refreshAndAnalyze(propertyId));
    }

    /** 다음 요청 가능 시각. 초 단위로 올림한다 — 내림하면 그 시각에 다시 요청해도 429 다. */
    private static OffsetDateTime retryAfter(Duration remaining) {
        OffsetDateTime at = OffsetDateTime.now(SEOUL).plus(remaining);
        OffsetDateTime truncated = at.truncatedTo(ChronoUnit.SECONDS);
        return truncated.equals(at) ? truncated : truncated.plusSeconds(1);
    }
}
