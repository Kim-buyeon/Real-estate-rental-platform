package com.duri.rentalplatform.domain.property.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.external.address.AddressNormalizeClient;
import com.duri.rentalplatform.external.address.NormalizedAddress;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerClient;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDocument;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerLookup;
import java.util.Optional;
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
 */
@Service
public class LedgerCommandService {

    private final BuildingLedgerClient buildingLedgerClient;
    private final AddressNormalizeClient addressNormalizeClient;
    private final PropertyRepository propertyRepository;
    private final BuildingLedgerRepository buildingLedgerRepository;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    public LedgerCommandService(
            BuildingLedgerClient buildingLedgerClient,
            AddressNormalizeClient addressNormalizeClient,
            PropertyRepository propertyRepository,
            BuildingLedgerRepository buildingLedgerRepository,
            PlatformTransactionManager transactionManager) {
        this.buildingLedgerClient = buildingLedgerClient;
        this.addressNormalizeClient = addressNormalizeClient;
        this.propertyRepository = propertyRepository;
        this.buildingLedgerRepository = buildingLedgerRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 대장을 아직 수집하지 않았으면 떼어 저장한다.
     *
     * @throws BusinessException {@link ErrorCode#PROPERTY_NOT_FOUND} — 매물이 없을 때,
     *                           {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 연동이 실패하거나 서킷이 열려 있을 때
     */
    public void collectIfAbsent(Long propertyId) {
        Optional<BuildingLedgerLookup> lookup = readTransaction.execute(status -> findUncollected(propertyId));
        if (lookup == null || lookup.isEmpty()) {
            return;
        }

        Optional<BuildingLedgerDocument> fetched = buildingLedgerClient.fetch(lookup.get());
        if (fetched.isEmpty()) {
            // 뗄 대장이 없다. 저장하지 않고 끝낸다(클래스 주석).
            return;
        }
        BuildingLedgerDocument document = normalizeAddress(fetched.get());

        try {
            writeTransaction.executeWithoutResult(status -> save(propertyId, document));
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반이면 다른 요청이 먼저 저장한 것이다. 그 밖의 무결성 위반은 삼키지 않는다.
            if (!buildingLedgerRepository.existsByPropertyId(propertyId)) {
                throw e;
            }
        }
    }

    /** 수집할 매물이면 조회 값을, 이미 수집했으면 빈 값을 돌려준다. 매물 유형은 지연 로딩이라 트랜잭션 안에서 꺼낸다. */
    private Optional<BuildingLedgerLookup> findUncollected(Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (buildingLedgerRepository.existsByPropertyId(propertyId)) {
            return Optional.empty();
        }
        return Optional.of(new BuildingLedgerLookup(
                property.getPropertyId(),
                property.naturalKey(),
                property.getLandlordName(),
                PropertyType.valueOf(property.getPropertyTypeCode().getCodeValue()),
                property.ledgerKey()));
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
