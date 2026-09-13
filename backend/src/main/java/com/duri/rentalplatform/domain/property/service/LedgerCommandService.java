package com.duri.rentalplatform.domain.property.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
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
 * <p><b>동시 첫 조회</b> — {@code building_ledger.property_id} 의 UNIQUE 제약이 늦은 쪽을 막고, 늦은 쪽은 먼저
 * 저장된 것을 그대로 쓴다. Mock 은 같은 매물에 같은 대장을 내므로 어느 쪽이 남아도 내용이 같다.
 */
@Service
public class LedgerCommandService {

    private final BuildingLedgerClient buildingLedgerClient;
    private final PropertyRepository propertyRepository;
    private final BuildingLedgerRepository buildingLedgerRepository;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    public LedgerCommandService(
            BuildingLedgerClient buildingLedgerClient,
            PropertyRepository propertyRepository,
            BuildingLedgerRepository buildingLedgerRepository,
            PlatformTransactionManager transactionManager) {
        this.buildingLedgerClient = buildingLedgerClient;
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

        BuildingLedgerDocument document = buildingLedgerClient.fetch(lookup.get());

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
                PropertyType.valueOf(property.getPropertyTypeCode().getCodeValue())));
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
