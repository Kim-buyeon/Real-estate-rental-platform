package com.duri.rentalplatform.domain.risk.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.risk.entity.BuildingRegistry;
import com.duri.rentalplatform.domain.risk.entity.MortgageHistory;
import com.duri.rentalplatform.domain.risk.entity.OwnershipHistory;
import com.duri.rentalplatform.domain.risk.repository.BuildingRegistryRepository;
import com.duri.rentalplatform.domain.risk.repository.MortgageHistoryRepository;
import com.duri.rentalplatform.domain.risk.repository.OwnershipHistoryRepository;
import com.duri.rentalplatform.external.registry.RegistryClient;
import com.duri.rentalplatform.external.registry.RegistryDocument;
import com.duri.rentalplatform.external.registry.RegistryLookup;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 등기 수집. 매물의 등기를 처음 조회할 때 한 번 떼어 DB 에 보관한다.
 *
 * <p><b>트랜잭션을 애노테이션이 아니라 {@link TransactionTemplate} 으로 가르는 이유</b> — 한 흐름 안에서
 * 「읽기 → 외부 호출 → 저장」 순서가 필요하고, 외부 호출은 트랜잭션 밖에 있어야 한다({@code backend/CLAUDE.md}
 * Service). 메서드에 {@code @Transactional} 을 붙이면 호출까지 트랜잭션에 들어가고, 저장만 다른 메서드로 떼면
 * 같은 클래스 안 호출이라 프록시를 거치지 않아 트랜잭션이 걸리지 않는다. 저장을 다른 빈으로 떼는 방식은 초기
 * 적재에만 허용된 예외다. 그래서 경계를 코드로 긋는다. 저장 블록 하나가 하나의 쓰기 트랜잭션이다.
 *
 * <p><b>동시 첫 조회</b> — 두 인스턴스가 같은 매물을 동시에 처음 조회하면 둘 다 「없음」을 보고 둘 다
 * 저장한다. {@code building_registry.property_id} 의 UNIQUE 제약이 늦은 쪽을 막고, 늦은 쪽은 먼저 저장된 것을
 * 그대로 쓴다. Mock 은 같은 매물에 같은 등기를 내므로 어느 쪽이 남아도 내용이 같다.
 */
@Service
public class RegistryCommandService {

    private final RegistryClient registryClient;
    private final PropertyRepository propertyRepository;
    private final BuildingRegistryRepository buildingRegistryRepository;
    private final OwnershipHistoryRepository ownershipHistoryRepository;
    private final MortgageHistoryRepository mortgageHistoryRepository;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    public RegistryCommandService(
            RegistryClient registryClient,
            PropertyRepository propertyRepository,
            BuildingRegistryRepository buildingRegistryRepository,
            OwnershipHistoryRepository ownershipHistoryRepository,
            MortgageHistoryRepository mortgageHistoryRepository,
            PlatformTransactionManager transactionManager) {
        this.registryClient = registryClient;
        this.propertyRepository = propertyRepository;
        this.buildingRegistryRepository = buildingRegistryRepository;
        this.ownershipHistoryRepository = ownershipHistoryRepository;
        this.mortgageHistoryRepository = mortgageHistoryRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 등기를 아직 수집하지 않았으면 떼어 저장한다.
     *
     * @throws BusinessException {@link ErrorCode#PROPERTY_NOT_FOUND} — 매물이 없을 때
     */
    public void collectIfAbsent(Long propertyId) {
        Optional<RegistryLookup> lookup = readTransaction.execute(status -> findUncollected(propertyId));
        if (lookup == null || lookup.isEmpty()) {
            return;
        }

        RegistryDocument document = registryClient.fetch(lookup.get());

        try {
            writeTransaction.executeWithoutResult(status -> save(propertyId, document));
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반이면 다른 요청이 먼저 저장한 것이다. 그 밖의 무결성 위반은 삼키지 않는다.
            if (!buildingRegistryRepository.existsByPropertyId(propertyId)) {
                throw e;
            }
        }
    }

    /**
     * 수집할 매물이면 조회 값을, 이미 수집했으면 빈 값을 돌려준다. 매물 유형은 지연 로딩이라 트랜잭션 안에서 꺼낸다.
     */
    private Optional<RegistryLookup> findUncollected(Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (buildingRegistryRepository.existsByPropertyId(propertyId)) {
            return Optional.empty();
        }
        return Optional.of(new RegistryLookup(
                property.getPropertyId(),
                property.naturalKey(),
                property.getLandlordName(),
                PropertyType.valueOf(property.getPropertyTypeCode().getCodeValue()),
                property.getMarketPrice()));
    }

    private void save(Long propertyId, RegistryDocument document) {
        // saveAndFlush — 제약 위반을 이 블록 안에서 드러내 이력 행을 넣기 전에 멈춘다.
        BuildingRegistry registry = buildingRegistryRepository.saveAndFlush(BuildingRegistry.collect(
                propertyId, document.buildingPurpose(), document.buildingStructure(), document.registryAddress(),
                document.exclusiveArea(), document.dataSource()));

        ownershipHistoryRepository.saveAll(document.ownerships().stream()
                .map(entry -> OwnershipHistory.record(registry, entry.rankNo(), entry.rightType(),
                        entry.holderName(), entry.receivedDate(), entry.cause(), entry.isActive()))
                .toList());

        mortgageHistoryRepository.saveAll(document.mortgages().stream()
                .map(entry -> MortgageHistory.record(registry, entry.rankNo(), entry.rightType(),
                        entry.creditor(), entry.debtorName(), entry.receivedDate(), entry.cause(),
                        entry.loanAmount(), entry.maxClaimAmount(), entry.priorTenantDeposit(), entry.isActive()))
                .toList());
    }
}
