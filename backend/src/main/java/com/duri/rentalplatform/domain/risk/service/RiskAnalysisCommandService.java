package com.duri.rentalplatform.domain.risk.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.cache.CriteriaVersionWatcher;
import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.service.LedgerCommandService;
import com.duri.rentalplatform.domain.risk.cache.JudgementCriteriaCache;
import com.duri.rentalplatform.domain.risk.calculator.DocumentConsistencyChecker;
import com.duri.rentalplatform.domain.risk.calculator.GuaranteeEligibilityJudge;
import com.duri.rentalplatform.domain.risk.calculator.NegativeEquityCalculator;
import com.duri.rentalplatform.domain.risk.calculator.RightViolationDetector;
import com.duri.rentalplatform.domain.risk.calculator.RiskGradeCalculator;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.entity.BuildingRegistry;
import com.duri.rentalplatform.domain.risk.entity.RiskAnalysis;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.HouseType;
import com.duri.rentalplatform.domain.risk.event.RiskGradeChangedEvent;
import com.duri.rentalplatform.domain.risk.repository.BuildingRegistryRepository;
import com.duri.rentalplatform.domain.risk.repository.MortgageHistoryRepository;
import com.duri.rentalplatform.domain.risk.repository.OwnershipHistoryRepository;
import com.duri.rentalplatform.domain.risk.repository.RiskAnalysisRepository;
import com.duri.rentalplatform.domain.risk.vo.ConsistencyInput;
import com.duri.rentalplatform.domain.risk.vo.ConsistencyResult;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeInput;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeJudgementResult;
import com.duri.rentalplatform.domain.risk.vo.JudgementCriteria;
import com.duri.rentalplatform.domain.risk.vo.MortgageEntry;
import com.duri.rentalplatform.domain.risk.vo.NegativeEquityResult;
import com.duri.rentalplatform.domain.risk.vo.OwnershipRightEntry;
import com.duri.rentalplatform.domain.risk.vo.ProviderJudgement;
import com.duri.rentalplatform.domain.risk.vo.RightViolationResult;
import com.duri.rentalplatform.domain.risk.vo.RiskGradeResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 위험도 분석(RISK-01). 등기 · 대장을 수집하고 RISK-02 ~ 05 판정을 이어 등급을 정한 뒤, 결론이 바뀌었을 때만 이력을 남긴다.
 * 조회({@link #findLatestOrAnalyze})는 판정 입력이 그대로이면 판정하지 않고 최신 분석 행에 적어 둔 근거를 돌려준다.
 *
 * <p><b>저장된 판정을 돌려주는 조건</b> — 데이터 적재 설계서 1.2. 셋이 모두 맞을 때다.
 * <ul>
 *   <li>최신 분석 행에 근거 JSON 이 있다 — V21 이전 행 · 근거를 읽지 못한 행은 다시 판정해 채운다</li>
 *   <li>그 행의 기준 지문이 지금 기준표({@link JudgementCriteriaCache})의 지문과 같다 — 관리자 수정 등으로 기준이 바뀌었으면 다시
 *       판정한다</li>
 *   <li>매물이 재분석 대기가 아니다(V18) — 판정 입력(시세 · 등기 · 대장)이 마지막 판정 뒤에 바뀌었으면 다시 판정한다</li>
 * </ul>
 *
 * <p><b>재분석 대기 표시</b> — 판정 입력을 바꾸는 쓰기(시세 갱신 · 등기 이력 교체 · 대장 수집 · 교체 · 삭제)가 그 쓰기와 같은
 * 트랜잭션에서 세우고, 판정이 성공해 기록하는 쓰기 트랜잭션이 내린다. 그래서 입력을 바꾼 뒤 판정이 실패해도 표시가 남아 저장된
 * 판정이 나가지 않는다 — 다음 조회가 다시 판정하고, 밤 갱신 배치의 재분석 갈래도 다시 잡는다. 내릴 때는 판정에 쓴 시세가 지금
 * 시세와 같을 때만이다({@code PropertyRepository#clearReanalysisPending}). 등기 · 대장 변경이 판정과 겹치는 경우는 막지 않는다 —
 * 등기 재조회 · 대장 교체는 매물 분석 락 안에서 바꾼 뒤 스스로 다시 판정한다.
 *
 * <p><b>지문이 다를 때</b> — 다시 판정하기 전에 버전 키를 한 번 확인한다({@link CriteriaVersionWatcher}, 슬롯당 확인 간격에 한 번).
 * 다른 슬롯이 방금 바뀐 기준으로 판정해 적었는데 이 슬롯이 아직 옛 기준을 들고 있으면, 옛 기준으로 다시 판정해 덮고 다음 조회가 또
 * 덮는 왕복이 생긴다. 확인으로 새 기준을 읽어 지문이 같아지면 저장된 판정을 그대로 낸다. 반복 작업의 확인 간격만큼의 창을 줄일
 * 뿐, 어느 지문이 더 새로운지는 가리지 않는다.
 *
 * <p><b>트랜잭션</b> — 저장된 판정을 돌려주는 조회는 트랜잭션을 열지 않는다(매물 · 최신 분석 행을 저장소 기본 트랜잭션으로 한 건씩
 * 읽는다). 판정할 때는 이렇다. 등기 · 대장 수집은 외부 호출을 포함하므로 이 서비스의 트랜잭션 밖에서 먼저 부른다(두 수집
 * 서비스는 각자 {@link TransactionTemplate} 으로 경계를 긋고, 여기서는 아무 트랜잭션도 열지 않은 상태로 부르므로
 * 중첩되지 않는다). 수집 실패(503)는 잡지 않고 올린다. 그 뒤 입력 읽기 · 판정 · 저장을 쓰기 트랜잭션 하나로 묶는다 —
 * 기존 최신 행을 내리는 UPDATE 와 새 행 INSERT 가 한 경계다. 방식은 등기 수집과 같은 이유로 코드로 긋는다. 기준표는 이 경계를
 * 열기 전에 슬롯 캐시에서 받는다.
 *
 * <p><b>저장 기준</b> — 최신 분석과 등급 · 3사 가입 · 저장 전세가율이 모두 같으면 새 행을 남기지 않는다. 다르거나 없으면
 * 기존 최신을 이력으로 내리고({@code is_latest = false}) 새 행을 최신으로 넣는다({@code previous_grade} = 이전 등급). 어느 쪽이든
 * 최신 행에 이번 판정의 근거 JSON · 기준 지문을 적는다 — 결론이 같아도 근거(보증한도 · 보증료 등)나 기준이 달라졌을 수 있다. 결론 ·
 * 근거 · 지문이 모두 같으면 아무것도 쓰지 않는다.
 *
 * <p><b>근거 JSON</b> — 응답에서 시세 셋 · 분석 시각을 뺀 {@link RiskResponse.Judgement} 를 Spring 이 등록한 {@link JsonMapper} 로
 * 적고 읽는다. 뺀 이유는 그 record 주석에 있다. 읽지 못하면 다시 판정한다 — 저장된 근거가 응답을 막지 않는다.
 *
 * <p><b>등급 변경 이벤트</b> — 새 최신 행을 남길 때 직전 최신 행이 있고 등급이 다르면 {@link RiskGradeChangedEvent} 를
 * 같은 쓰기 트랜잭션 안에서 발행한다. 조회 · 재분석 · 배치의 판정이 모두 이 지점을 지나므로 어느 경로로 등급이 바뀌어도 빠지지
 * 않는다. 저장된 판정을 돌려준 조회는 판정하지 않았으므로 발행할 것이 없다. 전세가율만 바뀐 새 행과 첫 분석은 등급 변경이
 * 아니라 발행하지 않는다. 동시 첫 분석으로 저장이 막히면 발행 전에 예외가 나고, 다시 판정한 쪽은 「결론 같음」이라 발행하지
 * 않는다.
 *
 * <p><b>동시 분석</b> — 두 인스턴스가 같은 매물을 동시에 처음 분석하면 한쪽이 최신 분석 유일 인덱스
 * ({@code uq_risk_analysis_latest}, V9 · V19 커버링 교체)에 걸린다. 그때는 한 번 다시 판정한다 — 다른 쪽이 같은 입력으로 저장했으므로
 * 「결론 같음 — 저장 안 함」으로 끝난다.
 *
 * <p><b>Mock 대장</b> — 대장 연동이 real({@code external.building-ledger.mode=real})이면 {@code data_source = MOCK} 인 대장은
 * 판정 입력에서 「대장 없음」으로 본다. 대장 항목 셋(주소 · 면적 · 위반건축물)은 확인 불가이고 분석 행의 대장 참조는 null 이다 —
 * 뗄 대장이 없을 때와 같은 경로다. Mock 대장은 판정 분기를 돌리려고 지어낸 값(위반건축물 5% 등)이라, real 운영에서 판정 근거가
 * 되면 없는 위반건축물로 가입 불가가 나온다. 행은 지우지 않는다 — 교체 배치가 실데이터로 바꾸거나 지운다. Mock · Fault 모드는
 * Mock 대장이 곧 그 모드의 대장이라 그대로 쓴다. 연동 모드는 기준 지문에 들어가므로 모드를 바꿔 기동하면 저장된 판정을 쓰지 않는다.
 */
@Slf4j
@Service
public class RiskAnalysisCommandService {

    private final RegistryCommandService registryCommandService;
    private final LedgerCommandService ledgerCommandService;
    private final PropertyRepository propertyRepository;
    private final BuildingRegistryRepository buildingRegistryRepository;
    private final OwnershipHistoryRepository ownershipHistoryRepository;
    private final MortgageHistoryRepository mortgageHistoryRepository;
    private final BuildingLedgerRepository buildingLedgerRepository;
    private final RiskAnalysisRepository riskAnalysisRepository;
    private final JudgementCriteriaCache judgementCriteriaCache;
    private final CriteriaVersionWatcher criteriaVersionWatcher;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate writeTransaction;
    private final boolean ignoreMockLedger;

    public RiskAnalysisCommandService(
            RegistryCommandService registryCommandService,
            LedgerCommandService ledgerCommandService,
            PropertyRepository propertyRepository,
            BuildingRegistryRepository buildingRegistryRepository,
            OwnershipHistoryRepository ownershipHistoryRepository,
            MortgageHistoryRepository mortgageHistoryRepository,
            BuildingLedgerRepository buildingLedgerRepository,
            RiskAnalysisRepository riskAnalysisRepository,
            JudgementCriteriaCache judgementCriteriaCache,
            CriteriaVersionWatcher criteriaVersionWatcher,
            JsonMapper jsonMapper,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            @Value("${external.building-ledger.mode:mock}") String buildingLedgerMode) {
        this.registryCommandService = registryCommandService;
        this.ledgerCommandService = ledgerCommandService;
        this.propertyRepository = propertyRepository;
        this.buildingRegistryRepository = buildingRegistryRepository;
        this.ownershipHistoryRepository = ownershipHistoryRepository;
        this.mortgageHistoryRepository = mortgageHistoryRepository;
        this.buildingLedgerRepository = buildingLedgerRepository;
        this.riskAnalysisRepository = riskAnalysisRepository;
        this.judgementCriteriaCache = judgementCriteriaCache;
        this.criteriaVersionWatcher = criteriaVersionWatcher;
        this.jsonMapper = jsonMapper;
        this.eventPublisher = eventPublisher;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.ignoreMockLedger = "real".equals(buildingLedgerMode);
    }

    /**
     * 매물의 최신 위험도를 명세 1.1 응답으로 돌려준다. 판정 입력이 그대로이면 저장된 판정을, 아니면 {@link #analyze} 로 판정한
     * 결과를 낸다(조건은 클래스 주석). 위험도 조회(GET)가 부른다.
     *
     * @throws BusinessException {@link ErrorCode#PROPERTY_NOT_FOUND} — 매물이 없을 때,
     *                           {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 판정해야 하는데 등기 · 대장 수집이 실패했을 때
     */
    public RiskResponse findLatestOrAnalyze(Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        return findLatestOrAnalyze(property);
    }

    /**
     * 이미 읽은 매물로 {@link #findLatestOrAnalyze(Long)} 를 한다. 매물을 먼저 읽어 404 를 가르는 호출부(대출 한도 조회)가 같은 매물을
     * 두 번 읽지 않게 한다. 저장된 판정에는 매물의 시세 셋 · 재분석 대기 표시만 쓰므로 트랜잭션 밖에서 읽은 매물이어도 된다 — 판정할
     * 때는 쓰기 트랜잭션 안에서 다시 읽는다.
     */
    public RiskResponse findLatestOrAnalyze(Property property) {
        return findStoredJudgement(property).orElseGet(() -> analyze(property.getPropertyId()));
    }

    /**
     * 매물의 위험도를 분석하고 명세 1.1 응답으로 돌려준다. 저장된 판정과 무관하게 늘 판정한다 — 재분석 요청 · 배치가 부르고, 조회는
     * 저장된 판정을 쓸 수 없을 때 부른다.
     *
     * @throws BusinessException {@link ErrorCode#PROPERTY_NOT_FOUND} — 매물이 없을 때,
     *                           {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 등기 · 대장 수집이 실패했을 때
     */
    public RiskResponse analyze(Long propertyId) {
        registryCommandService.collectIfAbsent(propertyId);
        ledgerCommandService.collectIfAbsent(propertyId);
        return judge(propertyId);
    }

    /**
     * 대장 수집을 건너뛰고 분석한다. 등기는 없으면 수집한다. 부르는 쪽은 둘이다.
     * <ul>
     *   <li>Mock 대장 교체 배치 — 대장을 방금 교체 · 삭제한 뒤. {@link #analyze} 로 부르면 삭제한 매물(뗄 대장 없음)의 대장을
     *       다시 떼어 같은 빈 값을 받느라 일일 호출 상한을 한 번 더 쓴다.</li>
     *   <li>매물 갱신 배치의 시세 변경 재판정 — 시세는 대장과 무관하다. {@link #analyze} 로 부르면 대장 없는 매물마다 건축HUB
     *       초당 한도를 기다려 판정 단계가 그 한도에 묶인다(#328).</li>
     * </ul>
     *
     * @throws BusinessException {@link ErrorCode#PROPERTY_NOT_FOUND} — 매물이 없을 때,
     *                           {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 등기 수집이 실패했을 때
     */
    public RiskResponse analyzeWithCollectedLedger(Long propertyId) {
        registryCommandService.collectIfAbsent(propertyId);
        return judge(propertyId);
    }

    /** 저장된 판정을 그대로 돌려줘도 되면 그 응답, 아니면 빈 값(클래스 주석의 세 조건). */
    private Optional<RiskResponse> findStoredJudgement(Property property) {
        if (property.isReanalysisPending()) {
            return Optional.empty();
        }
        Optional<RiskAnalysis> latest = riskAnalysisRepository.findByPropertyIdAndLatestTrue(property.getPropertyId());
        if (latest.isEmpty() || latest.get().getJudgementSnapshot() == null) {
            return Optional.empty();
        }
        RiskAnalysis analysis = latest.get();
        if (!matchesCurrentCriteria(analysis)) {
            return Optional.empty();
        }
        return readJudgement(analysis).map(judgement -> RiskResponse.of(judgement, property.getMarketPrice(),
                property.getPriceType(), property.getPriceDate(), analysis.getCreatedAt()));
    }

    /**
     * 저장된 지문이 지금 기준의 지문과 같은가. 다르면 버전 키를 한 번 확인해 기준이 바뀌었으면 다시 읽고 한 번 더 본다(클래스 주석
     * 「지문이 다를 때」). 확인이 간격에 막히거나 Redis 가 실패하면 그대로 「다름」이다.
     */
    private boolean matchesCurrentCriteria(RiskAnalysis analysis) {
        String stored = analysis.getCriteriaFingerprint();
        if (judgementCriteriaCache.current().fingerprint().equals(stored)) {
            return true;
        }
        return criteriaVersionWatcher.reloadIfVersionChangedThrottled()
                && judgementCriteriaCache.current().fingerprint().equals(stored);
    }

    /** 근거 JSON 을 읽는다. 읽지 못하면 기록하고 빈 값 — 다시 판정해 새로 적는다. */
    private Optional<RiskResponse.Judgement> readJudgement(RiskAnalysis analysis) {
        try {
            return Optional.of(jsonMapper.readValue(analysis.getJudgementSnapshot(), RiskResponse.Judgement.class));
        } catch (JacksonException e) {
            log.warn("저장된 판정 근거를 읽지 못해 다시 판정한다. riskId={}", analysis.getRiskId(), e);
            return Optional.empty();
        }
    }

    private RiskResponse judge(Long propertyId) {
        // 기준표는 쓰기 트랜잭션을 열기 전에 받는다. 캐시가 비어 있어 DB 에서 읽게 되더라도 그 읽기가 쓰기 경계에 끼지 않는다.
        JudgementCriteria criteria = judgementCriteriaCache.current();
        try {
            return writeTransaction.execute(status -> judgeAndRecord(propertyId, criteria));
        } catch (DataIntegrityViolationException concurrentFirstAnalysis) {
            // 두 인스턴스가 같은 매물을 동시에 처음 분석하면 한쪽이 최신 분석 유일 인덱스(V9 · V19 커버링 교체)에 걸린다.
            // 다른 쪽이 이미 같은 입력으로 저장했으므로 다시 판정하면 「결과 같음 — 저장 안 함」으로 끝난다.
            return writeTransaction.execute(status -> judgeAndRecord(propertyId, criteria));
        }
    }

    private RiskResponse judgeAndRecord(Long propertyId, JudgementCriteria criteria) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        // 수집 직후라 없으면 수집 경로나 시드의 결함이다. 사용자 요청으로 생기는 상태가 아니므로 500 으로 낸다.
        BuildingRegistry registry = buildingRegistryRepository.findByPropertyId(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        // 대장은 없을 수 있다 — 뗄 대장이 없으면 수집이 저장하지 않는다(LedgerCommandService). 그때는 대장 없이 분석하고
        // 대장 항목은 확인 불가로 둔다(DocumentConsistencyChecker).
        // real 연동에서 Mock 대장은 대장 없음으로 본다(클래스 주석).
        BuildingLedger ledger = buildingLedgerRepository.findByPropertyId(propertyId)
                .filter(found -> !(ignoreMockLedger && found.isMock()))
                .orElse(null);

        List<OwnershipRightEntry> ownerships = ownershipHistoryRepository.findByRegistry(registry).stream()
                .map(history -> new OwnershipRightEntry(history.getRankNo(), history.getRightType(),
                        history.getOwnerName(), history.isCurrent()))
                .toList();
        List<MortgageEntry> mortgages = mortgageHistoryRepository.findByRegistry(registry).stream()
                .map(history -> new MortgageEntry(history.getMaxBondAmount(), history.getPriorTenantDeposit(),
                        history.isSeniorDebt(), history.isActive()))
                .toList();

        long deposit = property.getDeposit();
        long marketPrice = property.getMarketPrice();

        NegativeEquityResult negativeEquity = NegativeEquityCalculator.calculate(
                mortgages, deposit, marketPrice, criteria.negativeEquityRatio());
        RightViolationResult rights = RightViolationDetector.detect(ownerships);
        ConsistencyResult consistency = DocumentConsistencyChecker.check(new ConsistencyInput(
                ownerships, property.getLandlordName(), ledger != null,
                ledger == null ? null : ledger.getLedgerAddress(), registry.getRegistryAddress(),
                ledger == null ? null : ledger.getExclusiveArea(), registry.getExclusiveArea(),
                ledger == null ? null : ledger.getViolation()));

        GuaranteeJudgementResult guarantee = GuaranteeEligibilityJudge.judge(
                new GuaranteeInput(marketPrice, deposit, negativeEquity.seniorDebtTotal(), houseType(property),
                        consistency.violationBuilding(), rights.rightViolations(), consistency.ownerNameMatched(),
                        consistency.addressMatched()),
                criteria.guaranteeCriteria());

        RiskGradeResult grade = RiskGradeCalculator.calculate(
                negativeEquity.negativeEquity(), guarantee.insuranceEligible(),
                negativeEquity.seniorDebtTotal() + deposit, marketPrice, criteria.cautionLeaseRatio());

        // 분석 시각은 저장 뒤에 정해진다. 근거는 분석 시각과 무관하므로 먼저 만들어 저장하고, 응답에 시각을 붙인다.
        RiskResponse.Judgement judgement = RiskResponse.of(grade, negativeEquity, guarantee, rights, consistency,
                property.getMarketPrice(), property.getPriceType(), property.getPriceDate(), null).judgement();
        LocalDateTime analyzedAt = record(propertyId, registry, ledger, negativeEquity, guarantee, grade,
                criteria, judgement);
        // 판정 기록과 한 트랜잭션에서 재분석 대기를 내린다 — 판정이 실패해 롤백되면 표시가 남는다(클래스 주석). 엔티티로 내리지
        // 않는다 — 변경 감지가 매물의 모든 열을 이 트랜잭션이 읽은 값으로 다시 쓴다(PropertyRepository#markReanalysisPending).
        propertyRepository.clearReanalysisPending(propertyId, marketPrice);

        return RiskResponse.of(judgement, property.getMarketPrice(), property.getPriceType(),
                property.getPriceDate(), analyzedAt);
    }

    /**
     * 결론이 바뀌었으면 새 최신 행을 남기고, 등급까지 바뀌었으면 이벤트를 발행한다. 어느 쪽이든 최신 행에 근거 · 지문을 적는다 —
     * 이미 같으면 쓰지 않는다. 최신 분석 행의 시각을 돌려준다.
     */
    private LocalDateTime record(Long propertyId, BuildingRegistry registry, BuildingLedger ledger,
            NegativeEquityResult negativeEquity, GuaranteeJudgementResult guarantee, RiskGradeResult grade,
            JudgementCriteria criteria, RiskResponse.Judgement judgement) {
        boolean hug = eligible(guarantee, GuaranteeProvider.HUG);
        boolean hf = eligible(guarantee, GuaranteeProvider.HF);
        boolean sgi = eligible(guarantee, GuaranteeProvider.SGI);
        String snapshot = writeJudgement(judgement);
        // 근거를 적지 못했으면 지문도 비운다 — 근거 없는 지문은 뜻이 없고, 다음 조회가 다시 판정해 채운다.
        String fingerprint = snapshot == null ? null : criteria.fingerprint();

        Optional<RiskAnalysis> latest = riskAnalysisRepository.findByPropertyIdAndLatestTrue(propertyId);
        if (latest.isPresent()
                && latest.get().sameConclusion(grade.riskGrade(), hug, hf, sgi, negativeEquity.debtRatio())) {
            RiskAnalysis kept = latest.get();
            if (!kept.sameJudgement(snapshot, fingerprint)) {
                // 쓰기 트랜잭션 안의 관리 상태 엔티티라 변경 감지가 커밋 때 UPDATE 한다.
                kept.recordJudgement(snapshot, fingerprint);
            }
            return kept.getCreatedAt();
        }

        RiskGrade previousGrade = latest.map(RiskAnalysis::getRiskGrade).orElse(null);
        latest.ifPresent(RiskAnalysis::supersede);
        // 내린 행을 먼저 반영한다. 새 행 INSERT 가 먼저 나가면 한순간 최신 행이 둘이다.
        riskAnalysisRepository.flush();

        RiskAnalysis analysis = RiskAnalysis.record(
                propertyId, registry.getRegistryId(), ledger == null ? null : ledger.getLedgerId(),
                firstEligibleGuaranteeId(guarantee, criteria), negativeEquity.debtRatio(),
                hug, hf, sgi, grade.riskGrade(), grade.gradeReason(), previousGrade);
        analysis.recordJudgement(snapshot, fingerprint);
        RiskAnalysis saved = riskAnalysisRepository.save(analysis);
        if (previousGrade != null && previousGrade != saved.getRiskGrade()) {
            eventPublisher.publishEvent(new RiskGradeChangedEvent(
                    propertyId, previousGrade, saved.getRiskGrade(), saved.getCreatedAt()));
        }
        return saved.getCreatedAt();
    }

    /** 근거를 JSON 으로 적는다. 적지 못하면 기록하고 null — 판정 결과 저장은 막지 않는다. */
    private String writeJudgement(RiskResponse.Judgement judgement) {
        try {
            return jsonMapper.writeValueAsString(judgement);
        } catch (JacksonException e) {
            log.warn("판정 근거를 JSON 으로 적지 못했다 — 근거 없이 저장하고 다음 조회가 다시 판정한다", e);
            return null;
        }
    }

    /** 요율 표 주택유형 — 아파트 외(오피스텔 등)는 OTHER. */
    private static HouseType houseType(Property property) {
        PropertyType type = PropertyType.valueOf(property.getPropertyTypeCode().getCodeValue());
        return type == PropertyType.APARTMENT ? HouseType.APARTMENT : HouseType.OTHER;
    }

    private static boolean eligible(GuaranteeJudgementResult guarantee, GuaranteeProvider provider) {
        return guarantee.providers().stream()
                .anyMatch(judgement -> judgement.provider() == provider && judgement.eligible());
    }

    /** 가입 가능한 기관 중 HUG → HF → SGI 순 첫 기관의 기준 ID. 없으면 null. */
    private static Long firstEligibleGuaranteeId(GuaranteeJudgementResult guarantee, JudgementCriteria criteria) {
        return guarantee.providers().stream()
                .filter(ProviderJudgement::eligible)
                .map(ProviderJudgement::provider)
                .sorted()
                .findFirst()
                .map(criteria::guaranteeIdOf)
                .orElse(null);
    }
}
