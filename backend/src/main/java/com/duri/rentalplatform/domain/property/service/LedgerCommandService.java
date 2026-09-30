package com.duri.rentalplatform.domain.property.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.enums.LedgerReplacementOutcome;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.vo.LedgerReplacement;
import com.duri.rentalplatform.external.address.AddressNormalizeClient;
import com.duri.rentalplatform.external.address.NormalizedAddress;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerClient;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDailyQuota;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDocument;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerLookup;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerRateLimitedException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 건축물대장 수집. 매물의 대장을 처음 조회할 때 한 번 떼어 DB 에 보관한다.
 *
 * <p><b>트랜잭션을 {@link TransactionTemplate} 으로 가르는 이유</b> — 등기 수집({@code RegistryCommandService})과
 * 같다. 「읽기 → 외부 호출 → 저장」 순서에서 외부 호출만 트랜잭션 밖에 두어야 하는데, 애노테이션으로는 같은 클래스
 * 안에서 그 경계를 그을 수 없다.
 *
 * <p><b>연동 실패</b> — 클라이언트가 던진 {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} 을 잡지 않는다. 저장 블록에
 * 닿기 전에 올라가므로 아무것도 저장되지 않고, 다음 조회가 다시 수집을 시도한다. 빈 대장이나 기본값(위반건축물
 * 아님)으로 채워 저장하면 외부 장애가 판정 입력으로 남는다.
 *
 * <p><b>뗄 대장이 없을 때</b> — 조회 키가 없는 매물이거나 그 필지에 쓸 표제부가 없으면 클라이언트가 빈 값을 준다. 대장 행을
 * 지어 저장하지 않고 오류도 내지 않는다 — 위험도 분석은 대장 없이 진행하고(대장 항목은 확인 불가), 대장 조회는 대장 항목이
 * 빈 응답을 낸다. 저장하지 않으므로 갱신 배치가 키를 채운 뒤의 조회가 다시 수집한다.
 *
 * <p><b>대장 주소 정규화</b> — 명의 · 문서 정합(RISK-04)은 대장 주소와 등기 주소를 정규화된 주소끼리 완전 일치로 견준다.
 * 출처가 원문 주소를 주면({@code LedgerDataSource#isAddressNormalizationRequired}) 적재와 같은 주소 정규화에 태워 도로명
 * 주소로 저장한다 — 같은 필지는 적재 때와 같은 원문이라 같은 도로명 주소가 나온다. 정규화 결과가 없으면 원문을 그대로 둔다
 * (대조는 불일치로 나온다). 정규화 호출도 외부 호출이라 트랜잭션 밖에서 하고, 실패는 대장 연동 실패와 같이 올린다.
 *
 * <p><b>동시 첫 조회</b> — {@code building_ledger.property_id} 의 UNIQUE 제약이 늦은 쪽을 막고, 늦은 쪽은 먼저
 * 저장된 것을 그대로 쓴다. 같은 매물은 같은 대장이 나오므로 어느 쪽이 남아도 내용이 같다.
 *
 * <p><b>Mock 대장 교체</b> — 수집은 「없을 때만」이라 연동을 real 로 바꿔도 이미 저장된 Mock 대장은 그대로 남는다. 교체 배치가
 * {@link #fetchMockReplacement} 로 대장을 다시 떼어 보고, 뗐으면 {@link #replaceMock} 으로 같은 행을 바꾼다. 뗄 대장이 없을 때
 * 행을 지우는 일은 분석 이력의 참조를 함께 끊어야 해서 배치 쪽(위험도 도메인)이 한 트랜잭션으로 한다. 외부 호출과 저장을 나눈
 * 이유는 수집과 같다.
 *
 * <p><b>초당 한도</b> — 클라이언트가 {@link BuildingLedgerRateLimitedException} 을 던지면 장애가 아니라 한도다. 수집은 저장 없이
 * 끝내고(대장 없이 판정 — 일일 상한과 같다), 교체는 {@link LedgerReplacementOutcome#RATE_LIMITED} 로 Mock 행을 남긴다.
 * 빈 값(「없음」)으로 바꾸지 않는 이유는 그 예외의 주석에 있다.
 *
 * <p><b>대장 행이 없는 매물</b> — 교체 배치는 대장 행이 없고 조회 키가 있는 매물도 대상으로 삼는다. 그 매물은
 * {@link #fetchMockReplacement} 가 {@link LedgerReplacementOutcome#NO_LEDGER} 를 주고, 배치가 {@link #collectIfAbsent} 로
 * 수집한다 — 저장 경로를 따로 두지 않는다.
 */
@Slf4j
@Service
public class LedgerCommandService {

    private final BuildingLedgerClient buildingLedgerClient;
    private final AddressNormalizeClient addressNormalizeClient;
    private final PropertyRepository propertyRepository;
    private final BuildingLedgerRepository buildingLedgerRepository;
    private final BuildingLedgerDailyQuota dailyQuota;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    public LedgerCommandService(
            BuildingLedgerClient buildingLedgerClient,
            AddressNormalizeClient addressNormalizeClient,
            PropertyRepository propertyRepository,
            BuildingLedgerRepository buildingLedgerRepository,
            BuildingLedgerDailyQuota dailyQuota,
            PlatformTransactionManager transactionManager) {
        this.buildingLedgerClient = buildingLedgerClient;
        this.addressNormalizeClient = addressNormalizeClient;
        this.propertyRepository = propertyRepository;
        this.buildingLedgerRepository = buildingLedgerRepository;
        this.dailyQuota = dailyQuota;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 대장을 아직 수집하지 않았으면 떼어 저장한다.
     *
     * @return 이 호출이 대장 행을 저장했으면 참. 이미 있었거나 · 뗄 대장이 없거나 · 한도에 닿았거나 · 동시 요청이 먼저
     *         저장했으면 거짓
     * @throws BusinessException {@link ErrorCode#PROPERTY_NOT_FOUND} — 매물이 없을 때,
     *                           {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 연동이 실패하거나 서킷이 열려 있을 때
     */
    public boolean collectIfAbsent(Long propertyId) {
        Optional<BuildingLedgerLookup> lookup = readTransaction.execute(status -> findUncollected(propertyId));
        if (lookup == null || lookup.isEmpty()) {
            return false;
        }

        Optional<BuildingLedgerDocument> fetched;
        try {
            fetched = buildingLedgerClient.fetch(lookup.get());
        } catch (BuildingLedgerRateLimitedException e) {
            // 초당 한도. 장애가 아니므로 저장 없이 끝낸다 — 대장 없이 판정하고 다음 조회가 다시 수집한다(클래스 주석).
            log.info("[대장 수집] 초당 한도로 건너뜀 — 매물 {}", propertyId);
            return false;
        }
        if (fetched.isEmpty()) {
            // 뗄 대장이 없다. 저장하지 않고 끝낸다(클래스 주석).
            return false;
        }
        BuildingLedgerDocument document = normalizeAddress(fetched.get());

        try {
            writeTransaction.executeWithoutResult(status -> save(propertyId, document));
            return true;
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반이면 다른 요청이 먼저 저장한 것이다. 그 밖의 무결성 위반은 삼키지 않는다.
            if (!buildingLedgerRepository.existsByPropertyId(propertyId)) {
                throw e;
            }
            return false;
        }
    }

    /**
     * Mock 대장을 교체하려고 대장을 다시 떼어 본다. 저장하지 않는다.
     *
     * <p>일일 상한과 「뗄 대장 없음」은 클라이언트에서 같은 빈 값으로 온다. 조회 키가 있는 매물에서 빈 값이 왔고 그 뒤 상한이 남아
     * 있지 않으면 상한으로 본다 — 마지막 한 칸으로 「없음」을 받은 경우도 여기에 들지만, 그 매물은 Mock 을 남겨 다음 회차에 다시
     * 본다. 반대로 보면 상한에 걸린 매물의 Mock 행을 「없음」으로 지우게 된다. 조회 키가 없으면 클라이언트가 부르지 않으므로 상한과
     * 무관하게 「없음」이다. 초당 한도는 {@link LedgerReplacementOutcome#RATE_LIMITED} 이고, 대장 행이 없으면
     * {@link LedgerReplacementOutcome#NO_LEDGER} 다(떼지 않는다 — 배치가 수집 경로로 넘긴다).
     *
     * @throws BusinessException {@link ErrorCode#PROPERTY_NOT_FOUND} — 매물이 없을 때,
     *                           {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 대장 · 주소 정규화 연동이 실패했을 때
     */
    public LedgerReplacement fetchMockReplacement(Long propertyId) {
        MockLedgerState state = readTransaction.execute(status -> findMockLedgerLookup(propertyId));
        if (state == null || state.outcome() != null) {
            return LedgerReplacement.of(state == null ? LedgerReplacementOutcome.NOT_MOCK : state.outcome());
        }
        BuildingLedgerLookup lookup = state.lookup();
        boolean keyed = lookup.ledgerKey() != null;
        if (keyed && dailyQuota.remaining() <= 0) {
            return LedgerReplacement.of(LedgerReplacementOutcome.QUOTA_EXHAUSTED);
        }

        Optional<BuildingLedgerDocument> fetched;
        try {
            fetched = buildingLedgerClient.fetch(lookup);
        } catch (BuildingLedgerRateLimitedException e) {
            return LedgerReplacement.of(LedgerReplacementOutcome.RATE_LIMITED);
        }
        if (fetched.isEmpty()) {
            boolean quotaExhausted = keyed && dailyQuota.remaining() <= 0;
            return LedgerReplacement.of(quotaExhausted
                    ? LedgerReplacementOutcome.QUOTA_EXHAUSTED : LedgerReplacementOutcome.NOT_FOUND);
        }
        return LedgerReplacement.fetched(normalizeAddress(fetched.get()));
    }

    /**
     * 매물의 Mock 대장을 뗀 대장으로 바꾼다. 그 사이 다른 경로가 행을 지웠거나 이미 Mock 이 아니면 손대지 않는다.
     *
     * @param document {@link #fetchMockReplacement} 가 돌려준 대장
     * @return 바꿨으면 참
     */
    public boolean replaceMock(Long propertyId, BuildingLedgerDocument document) {
        Boolean replaced = writeTransaction.execute(status -> buildingLedgerRepository.findByPropertyId(propertyId)
                .filter(BuildingLedger::isMock)
                .map(ledger -> {
                    ledger.replaceWith(
                            document.ledgerAddress(),
                            document.ownerName(),
                            document.buildingPurpose(),
                            document.buildingStructure(),
                            document.buildingArea(),
                            document.totalFloorArea(),
                            document.exclusiveArea(),
                            document.approvalDate(),
                            document.violation(),
                            document.dataSource());
                    return true;
                })
                .orElse(false));
        return Boolean.TRUE.equals(replaced);
    }

    /** 대장이 Mock 이면 조회 값을, 대장 행이 없으면 {@code NO_LEDGER} 를, Mock 이 아니면 {@code NOT_MOCK} 을 담는다. */
    private MockLedgerState findMockLedgerLookup(Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        Optional<BuildingLedger> ledger = buildingLedgerRepository.findByPropertyId(propertyId);
        if (ledger.isEmpty()) {
            return new MockLedgerState(null, LedgerReplacementOutcome.NO_LEDGER);
        }
        return ledger.get().isMock()
                ? new MockLedgerState(lookupOf(property), null)
                : new MockLedgerState(null, LedgerReplacementOutcome.NOT_MOCK);
    }

    /** 교체 대상 확인 결과 — 떼어 볼 매물이면 조회 값, 아니면 그 결과. 둘 중 하나만 있다. */
    private record MockLedgerState(BuildingLedgerLookup lookup, LedgerReplacementOutcome outcome) {
    }

    /** 수집할 매물이면 조회 값을, 이미 수집했으면 빈 값을 돌려준다. */
    private Optional<BuildingLedgerLookup> findUncollected(Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (buildingLedgerRepository.existsByPropertyId(propertyId)) {
            return Optional.empty();
        }
        return Optional.of(lookupOf(property));
    }

    /** 매물에서 조회 값을 만든다. 매물 유형은 지연 로딩이라 트랜잭션 안에서 부른다. */
    private static BuildingLedgerLookup lookupOf(Property property) {
        return new BuildingLedgerLookup(
                property.getPropertyId(),
                property.naturalKey(),
                property.getLandlordName(),
                PropertyType.valueOf(property.getPropertyTypeCode().getCodeValue()),
                property.ledgerKey());
    }

    /** 출처가 원문 주소를 주면 도로명 주소로 바꾼다. 정규화 결과가 없으면 원문 그대로다. */
    private BuildingLedgerDocument normalizeAddress(BuildingLedgerDocument document) {
        if (!document.dataSource().isAddressNormalizationRequired() || document.ledgerAddress() == null) {
            return document;
        }
        String address = addressNormalizeClient.normalize(document.ledgerAddress())
                .map(NormalizedAddress::roadAddress)
                .orElse(document.ledgerAddress());
        return new BuildingLedgerDocument(
                address,
                document.ownerName(),
                document.buildingPurpose(),
                document.buildingStructure(),
                document.buildingArea(),
                document.totalFloorArea(),
                document.exclusiveArea(),
                document.approvalDate(),
                document.violation(),
                document.dataSource());
    }

    private void save(Long propertyId, BuildingLedgerDocument document) {
        buildingLedgerRepository.saveAndFlush(BuildingLedger.collect(
                propertyId,
                document.ledgerAddress(),
                document.ownerName(),
                document.buildingPurpose(),
                document.buildingStructure(),
                document.buildingArea(),
                document.totalFloorArea(),
                document.exclusiveArea(),
                document.approvalDate(),
                document.violation(),
                document.dataSource()));
    }
}
