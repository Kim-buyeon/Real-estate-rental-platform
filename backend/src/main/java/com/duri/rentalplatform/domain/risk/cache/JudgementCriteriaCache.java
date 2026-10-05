package com.duri.rentalplatform.domain.risk.cache;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.loan.entity.LoanProduct;
import com.duri.rentalplatform.domain.loan.entity.LoanRegulation;
import com.duri.rentalplatform.domain.loan.repository.LoanProductRepository;
import com.duri.rentalplatform.domain.loan.repository.LoanRegulationRepository;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitCriteria;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.risk.calculator.CriteriaFingerprintCalculator;
import com.duri.rentalplatform.domain.risk.entity.GuaranteeCriteria;
import com.duri.rentalplatform.domain.risk.entity.GuaranteePremiumRate;
import com.duri.rentalplatform.domain.risk.entity.HfCriteria;
import com.duri.rentalplatform.domain.risk.entity.InsuranceProduct;
import com.duri.rentalplatform.domain.risk.entity.RiskCriteria;
import com.duri.rentalplatform.domain.risk.entity.SgiCriteria;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.repository.GuaranteeCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.GuaranteePremiumRateRepository;
import com.duri.rentalplatform.domain.risk.repository.HfCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.InsuranceProductRepository;
import com.duri.rentalplatform.domain.risk.repository.RiskCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.SgiCriteriaRepository;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeCriteriaSnapshot;
import com.duri.rentalplatform.domain.risk.vo.JudgementCriteria;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판정 기준표 슬롯 캐시 — 위험도 판정 기준 6종(보증 기준 · HF · SGI · 보증료율 · 보증 상품 · 위험 등급 기준)과 대출 한도 기준(최신
 * 대출 규제 · 매물 유형별 대표 대출 상품)을 한 벌({@link JudgementCriteria})로 슬롯 메모리에 든다. 아키텍처 설계서(성능) 1.3.
 *
 * <p><b>왜 슬롯 메모리인가</b> — 「캐시는 외부 저장소」 전제의 예외다. 기준표는 관리자 수정 · 월 1회 금리 갱신으로만 바뀌는데 위험도 ·
 * 대출 요청마다 여러 표를 읽었다. Redis 에 통째로 두면 요청마다 Redis 왕복이 남는다. 슬롯마다 들되, 바뀐 것을 다른 슬롯이 알게
 * 하는 길을 둔다(아래 버전 키).
 *
 * <p><b>요청 경로</b> — {@link #current()} 는 Redis 를 부르지 않는다. Redis 가 느려도(타임아웃 2초) 위험도 요청이 기다리지 않는다.
 * 비어 있으면(기동 직후 · 무효화 직후) DB 에서 읽어 채운다. 동시에 비어 있는 것을 본 요청들은 하나만 읽고 나머지는 그 결과를 쓴다.
 *
 * <p><b>무효화</b> — 기준을 바꾸는 쪽(기준 수정 · 대출 금리 갱신)이 {@link #invalidateAfterCommit()} 를 부른다. <b>커밋 뒤</b>에
 * 자기 슬롯을 비우고 Redis 버전 키 {@value #VERSION_KEY} 를 올린다 — 커밋 전에 올리면 다른 슬롯이 옛 값을 다시 읽어 들 수 있다.
 * 다른 슬롯은 반복 작업({@code JudgementCriteriaRefreshScheduler})이 버전 키를 짧은 간격으로 읽어 바뀌었으면 다시 읽는다. 발행 ·
 * 구독을 쓰지 않은 이유 — 끊긴 동안 온 메시지는 사라진다({@code RedisSubscriptionStarter}). 버전 키는 끊겼다 이어져도 다음 확인에서
 * 바뀐 것을 잡는다.
 *
 * <p><b>Redis 실패</b> — 경고만 남기고 요청을 실패시키지 않는다. 버전 키를 못 올리면 다른 슬롯은 안전망(설정 간격마다 무조건 다시
 * 읽기)으로 따라온다. 그 간격이 슬롯 사이에 기준이 어긋날 수 있는 상한이다.
 *
 * <p><b>도메인</b> — 대출 기준을 함께 드는 것은 한 버전 키 · 한 번의 무효화로 두 쪽을 맞추려는 것이다. 기준 수정(ADMIN-01) 하나가
 * 위험 등급 기준과 대출 규제를 모두 고친다.
 */
@Slf4j
@Component
public class JudgementCriteriaCache {

    /** 기준표 버전 키. 값은 정수 문자열이고 뜻은 없다 — 바뀌었는지만 본다. 만료를 걸지 않는다. */
    public static final String VERSION_KEY = "criteria:version";

    private final GuaranteeCriteriaRepository guaranteeCriteriaRepository;
    private final HfCriteriaRepository hfCriteriaRepository;
    private final SgiCriteriaRepository sgiCriteriaRepository;
    private final GuaranteePremiumRateRepository premiumRateRepository;
    private final InsuranceProductRepository insuranceProductRepository;
    private final RiskCriteriaRepository riskCriteriaRepository;
    private final LoanRegulationRepository loanRegulationRepository;
    private final LoanProductRepository loanProductRepository;
    private final StringRedisTemplate redis;
    private final TransactionTemplate readTransaction;
    private final String buildingLedgerMode;

    /** 비어 있는 캐시를 한 요청만 채우게 한다. DB 읽기 동안 쥔다. */
    private final ReentrantLock loadLock = new ReentrantLock();
    /** {@link #current} · {@link #generation} 을 함께 바꾼다. 쥐는 동안 I/O 를 하지 않는다. */
    private final ReentrantLock stateLock = new ReentrantLock();

    private volatile JudgementCriteria current;
    /** 무효화마다 오른다. 읽기 시작 뒤 무효화가 있었으면 읽은 값을 담지 않는다 — 커밋 전에 읽은 옛 값일 수 있다. */
    private long generation;
    /** 마지막으로 본 버전 키 값. 반복 작업 한 스레드만 쓴다. */
    private volatile String seenVersion;
    /** 버전 키 읽기가 실패 중인가 — 1초마다 같은 경고를 쌓지 않으려고 바뀔 때만 기록한다. */
    private volatile boolean versionReadFailing;

    public JudgementCriteriaCache(
            GuaranteeCriteriaRepository guaranteeCriteriaRepository,
            HfCriteriaRepository hfCriteriaRepository,
            SgiCriteriaRepository sgiCriteriaRepository,
            GuaranteePremiumRateRepository premiumRateRepository,
            InsuranceProductRepository insuranceProductRepository,
            RiskCriteriaRepository riskCriteriaRepository,
            LoanRegulationRepository loanRegulationRepository,
            LoanProductRepository loanProductRepository,
            StringRedisTemplate redis,
            PlatformTransactionManager transactionManager,
            @Value("${external.building-ledger.mode:mock}") String buildingLedgerMode) {
        this.guaranteeCriteriaRepository = guaranteeCriteriaRepository;
        this.hfCriteriaRepository = hfCriteriaRepository;
        this.sgiCriteriaRepository = sgiCriteriaRepository;
        this.premiumRateRepository = premiumRateRepository;
        this.insuranceProductRepository = insuranceProductRepository;
        this.riskCriteriaRepository = riskCriteriaRepository;
        this.loanRegulationRepository = loanRegulationRepository;
        this.loanProductRepository = loanProductRepository;
        this.redis = redis;
        this.readTransaction = new TransactionTemplate(transactionManager);
        // 표 여럿을 커넥션 하나로 읽는다. 읽기 분산 대상이 아니다 — 무효화 직후 standby 에 아직 없는 값을 읽으면 그 값이 다음
        // 무효화까지 남는다. 읽기 전용 표시는 기본 풀로 간다(읽기 분산은 @ReplicaRead 표시로만 고른다).
        this.readTransaction.setReadOnly(true);
        this.buildingLedgerMode = buildingLedgerMode;
    }

    /**
     * 지금 기준표. Redis 를 부르지 않는다. 비어 있으면 DB 에서 읽어 채운다.
     *
     * @throws BusinessException {@link ErrorCode#INTERNAL_ERROR} — 위험 등급 기준 행이 없을 때(시드 결함)
     */
    public JudgementCriteria current() {
        JudgementCriteria cached = current;
        if (cached != null) {
            return cached;
        }
        loadLock.lock();
        try {
            cached = current;
            if (cached != null) {
                return cached;
            }
            return loadAndStore();
        } finally {
            loadLock.unlock();
        }
    }

    /**
     * 버전 키가 지난번에 본 값과 다르면 비우고 다시 읽는다. 반복 작업이 짧은 간격으로 부른다. 기동 뒤 첫 확인은 키가 있으면 늘 다르다고
     * 보아 한 번 다시 읽는다 — 기동과 첫 확인 사이에 다른 슬롯이 기준을 바꿨을 수 있다.
     */
    public void reloadIfVersionChanged() {
        String version;
        try {
            version = redis.opsForValue().get(VERSION_KEY);
        } catch (RuntimeException e) {
            if (!versionReadFailing) {
                versionReadFailing = true;
                log.warn("기준표 버전 키를 읽지 못했다 — 회복될 때까지 이 경고를 다시 남기지 않는다. 그동안 다른 슬롯의 기준 변경은 "
                        + "안전망 주기로 따라온다", e);
            }
            return;
        }
        if (versionReadFailing) {
            versionReadFailing = false;
            log.info("기준표 버전 키 읽기가 회복됐다");
        }
        if (Objects.equals(version, seenVersion)) {
            return;
        }
        seenVersion = version;
        invalidate();
        reload();
    }

    /**
     * 비우지 않고 다시 읽어 바꾼다 — 안전망. 버전 키를 못 올린 변경(Redis 장애 중 수정)도 이 주기 안에 따라온다. 읽기가 실패하면 지금
     * 값을 그대로 둔다.
     */
    public void reload() {
        long startedGeneration = generation();
        JudgementCriteria loaded;
        try {
            loaded = load();
        } catch (RuntimeException e) {
            log.warn("기준표를 다시 읽지 못했다 — 지금 값을 유지한다(비어 있으면 다음 요청이 읽는다)", e);
            return;
        }
        storeUnlessInvalidated(startedGeneration, loaded);
    }

    /**
     * 기준을 바꾼 트랜잭션이 커밋된 뒤 이 슬롯을 비우고 버전 키를 올린다. 트랜잭션 밖에서 부르면 곧바로 한다. Redis 실패는 경고만
     * 남긴다 — 수정 요청은 이미 커밋됐다.
     */
    public void invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishChange();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishChange();
            }
        });
    }

    private void publishChange() {
        invalidate();
        try {
            redis.opsForValue().increment(VERSION_KEY);
        } catch (RuntimeException e) {
            log.warn("기준표 버전 키를 올리지 못했다 — 다른 슬롯은 안전망 주기 안에 바뀐 기준을 읽는다", e);
        }
    }

    /** 이 슬롯을 비운다. 진행 중인 읽기의 결과도 담지 않게 한다. */
    void invalidate() {
        stateLock.lock();
        try {
            generation++;
            current = null;
        } finally {
            stateLock.unlock();
        }
    }

    private JudgementCriteria loadAndStore() {
        long startedGeneration = generation();
        JudgementCriteria loaded = load();
        storeUnlessInvalidated(startedGeneration, loaded);
        return loaded;
    }

    private long generation() {
        stateLock.lock();
        try {
            return generation;
        } finally {
            stateLock.unlock();
        }
    }

    private void storeUnlessInvalidated(long startedGeneration, JudgementCriteria loaded) {
        stateLock.lock();
        try {
            if (generation == startedGeneration) {
                current = loaded;
            }
        } finally {
            stateLock.unlock();
        }
    }

    private JudgementCriteria load() {
        return readTransaction.execute(status -> {
            RiskCriteria risk = riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
            // 응답의 기관 순서가 HUG → HF → SGI 다. 저장소가 돌려주는 순서에 기대지 않는다.
            List<GuaranteeCriteria> guaranteeRows = guaranteeCriteriaRepository.findAll().stream()
                    .sorted(Comparator.comparing(GuaranteeCriteria::getProvider))
                    .toList();
            List<GuaranteeCriteriaSnapshot> guaranteeCriteria = snapshots(guaranteeRows);
            Map<GuaranteeProvider, Long> guaranteeIds = guaranteeRows.stream()
                    .collect(Collectors.toMap(GuaranteeCriteria::getProvider, GuaranteeCriteria::getGuaranteeId));
            String fingerprint = CriteriaFingerprintCalculator.calculate(guaranteeCriteria, guaranteeIds,
                    risk.getNegativeEquityRatio(), risk.getCautionLeaseRatio(), buildingLedgerMode);
            return new JudgementCriteria(guaranteeCriteria, guaranteeIds, risk.getNegativeEquityRatio(),
                    risk.getCautionLeaseRatio(), loanLimitCriteria(), fingerprint);
        });
    }

    /** 기관 기준 테이블들을 판정기 입력으로 묶는다. 넘긴 기관 순서가 응답 순서다. */
    private List<GuaranteeCriteriaSnapshot> snapshots(List<GuaranteeCriteria> criteria) {
        Map<Long, HfCriteria> hf = hfCriteriaRepository.findAll().stream()
                .collect(Collectors.toMap(HfCriteria::getGuaranteeId, Function.identity()));
        Map<Long, SgiCriteria> sgi = sgiCriteriaRepository.findAll().stream()
                .collect(Collectors.toMap(SgiCriteria::getGuaranteeId, Function.identity()));
        // 요율 구간은 식별자 순으로 둔다. 판정기는 맞는 첫 구간을 고르고 지문도 이 순서로 펴므로, 저장소 순서에 따라 지문이
        // 슬롯마다 달라지지 않게 한다.
        Map<Long, List<GuaranteePremiumRate>> rates = premiumRateRepository.findAll().stream()
                .sorted(Comparator.comparing(GuaranteePremiumRate::getPremiumRateId))
                .collect(Collectors.groupingBy(GuaranteePremiumRate::getGuaranteeId));
        // 한 기관에 상품이 여럿이면 먼저 들어온 행을 쓴다.
        Map<Long, InsuranceProduct> products = insuranceProductRepository.findAll().stream()
                .sorted(Comparator.comparing(InsuranceProduct::getInsuranceId))
                .collect(Collectors.toMap(InsuranceProduct::getGuaranteeId, Function.identity(),
                        (first, second) -> first));

        return criteria.stream()
                .map(row -> {
                    Long id = row.getGuaranteeId();
                    InsuranceProduct product = products.get(id);
                    return new GuaranteeCriteriaSnapshot(
                            row.getProvider(),
                            row.getCollateralRatio(),
                            row.getSeniorDebtRatioLimit(),
                            row.getMaxDeposit(),
                            sgi.containsKey(id) && sgi.get(id).isApartmentUnlimited(),
                            row.isViolationDisqualify(),
                            row.isRightViolationDisqualify(),
                            hf.containsKey(id) && hf.get(id).isLoanLinkedRequired(),
                            product == null ? null : product.getProductName(),
                            rates.getOrDefault(id, List.of()).stream().map(GuaranteePremiumRate::toBand).toList());
                })
                .toList();
    }

    /** 매물 유형별 대출 한도 기준값. 규제 행이 없거나 그 유형의 대표 상품이 없으면 그 유형을 뺀다. */
    private Map<PropertyType, LoanLimitCriteria> loanLimitCriteria() {
        Optional<LoanRegulation> regulation =
                loanRegulationRepository.findFirstByOrderByEffectiveDateDescRegulationIdDesc();
        Map<PropertyType, LoanLimitCriteria> byType = new EnumMap<>(PropertyType.class);
        if (regulation.isEmpty()) {
            return byType;
        }
        for (PropertyType type : PropertyType.values()) {
            representativeProduct(type).ifPresent(product -> byType.put(type, new LoanLimitCriteria(
                    regulation.get().getDepositRatioLimit(), regulation.get().getGuaranteeCapNoHouse(),
                    regulation.get().getGuaranteeCapOneHouse(), regulation.get().getDsrLimit(),
                    regulation.get().getStressDsrRate(), product.getInterestRate(), product.getMaxLimit())));
        }
        return byType;
    }

    /** 매물 유형의 HF 금리 API 대표 행, 없으면 시드 예시 행. 선택 규칙은 {@link LoanProductRepository}. */
    private Optional<LoanProduct> representativeProduct(PropertyType propertyType) {
        return loanProductRepository
                .findFirstByHouseTypeOrderByBaseMonthDescLoanAmountDescInterestRateAscLoanIdAsc(propertyType)
                .or(loanProductRepository::findFirstByHouseTypeIsNullOrderByLoanIdAsc);
    }
}
