package com.duri.rentalplatform.domain.risk.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.risk.entity.BuildingRegistry;
import com.duri.rentalplatform.domain.risk.entity.MortgageHistory;
import com.duri.rentalplatform.domain.risk.entity.OwnershipHistory;
import com.duri.rentalplatform.domain.risk.enums.RegistryRefreshOutcome;
import com.duri.rentalplatform.domain.risk.event.RegistryChangedEvent;
import com.duri.rentalplatform.domain.risk.repository.BuildingRegistryRepository;
import com.duri.rentalplatform.domain.risk.repository.MortgageHistoryRepository;
import com.duri.rentalplatform.domain.risk.repository.OwnershipHistoryRepository;
import com.duri.rentalplatform.external.registry.RegistryClient;
import com.duri.rentalplatform.external.registry.RegistryDocument;
import com.duri.rentalplatform.external.registry.RegistryDocument.MortgageEntry;
import com.duri.rentalplatform.external.registry.RegistryDocument.OwnershipEntry;
import com.duri.rentalplatform.external.registry.RegistryLookup;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 등기 수집. 매물의 등기를 처음 조회할 때 한 번 떼어 DB 에 보관하고({@link #collectIfAbsent}), 재분석 · 점검 때 다시 떼어
 * 달라진 갑구 · 을구를 반영한다({@link #refresh}).
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
 *
 * <p><b>다시 떼기의 비교 기준</b> — 갑구 · 을구 각 항목의 도메인 의미 필드(순위 · 등기 목적 · 권리자 · 채무자 · 접수일 ·
 * 등기원인 · 금액 · 말소 여부)다. 행 식별자 · 기록 시각 · 목적에서 파생되는 여부 컬럼은 보지 않는다. 순서와 무관하게 항목의
 * 모음으로 비교한다. 표제부는 비교하지 않는다 — 변동 점검의 대상은 권리 관계다.
 *
 * <p><b>수집 시각</b> — 응답의 {@code collectedAt} 은 표제부 수정일시다. 이력만 바꾸면 표제부 행은 그대로라 감사 리스너가
 * 수정일시를 갱신하지 않으므로, 감사 기능({@link AuditingHandler})에 수정 표시를 맡겨 같은 트랜잭션에서 반영한다. 시각을
 * 코드에서 직접 넣지 않는다.
 */
@Service
public class RegistryCommandService {

    private final RegistryClient registryClient;
    private final PropertyRepository propertyRepository;
    private final BuildingRegistryRepository buildingRegistryRepository;
    private final OwnershipHistoryRepository ownershipHistoryRepository;
    private final MortgageHistoryRepository mortgageHistoryRepository;
    private final AuditingHandler auditingHandler;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    public RegistryCommandService(
            RegistryClient registryClient,
            PropertyRepository propertyRepository,
            BuildingRegistryRepository buildingRegistryRepository,
            OwnershipHistoryRepository ownershipHistoryRepository,
            MortgageHistoryRepository mortgageHistoryRepository,
            AuditingHandler auditingHandler,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.registryClient = registryClient;
        this.propertyRepository = propertyRepository;
        this.buildingRegistryRepository = buildingRegistryRepository;
        this.ownershipHistoryRepository = ownershipHistoryRepository;
        this.mortgageHistoryRepository = mortgageHistoryRepository;
        this.auditingHandler = auditingHandler;
        this.eventPublisher = eventPublisher;
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
     * 등기를 다시 떼어 저장된 갑구 · 을구와 비교한다. 다르면 이력을 교체하고 수집 시각을 갱신한 뒤
     * {@link RegistryChangedEvent} 를 발행하고, 저장된 등기가 없으면 새로 저장한다. 외부 호출은 트랜잭션 밖이다.
     *
     * <p><b>전제</b> — 같은 매물의 분산 락({@code risk:analysis:lock:{propertyId}}) 안에서만 호출한다. 락 없이 겹치면 둘 다
     * 저장된 이력과 달라진 것을 보고 각자 교체해 이력이 두 벌 생긴다.
     *
     * @return 반영 결과. 동시 첫 수집으로 다른 요청이 먼저 저장했어도 {@link RegistryRefreshOutcome#COLLECTED} 다 — 누가
     *         저장했든 이 호출 전에 없던 등기가 생겼다
     * @throws BusinessException {@link ErrorCode#PROPERTY_NOT_FOUND} — 매물이 없을 때. 외부 조회 실패는 잡지 않고 올린다
     */
    public RegistryRefreshOutcome refresh(Long propertyId) {
        RegistryLookup lookup = readTransaction.execute(status -> lookupOf(findProperty(propertyId)));

        RegistryDocument document = registryClient.fetch(lookup);

        try {
            return writeTransaction.execute(status -> replaceIfChanged(propertyId, document));
        } catch (DataIntegrityViolationException e) {
            // 새로 저장하려다 UNIQUE 에 걸렸으면 다른 요청이 먼저 저장한 것이다. 그 밖의 무결성 위반은 삼키지 않는다.
            if (!buildingRegistryRepository.existsByPropertyId(propertyId)) {
                throw e;
            }
            return RegistryRefreshOutcome.COLLECTED;
        }
    }

    /**
     * 수집할 매물이면 조회 값을, 이미 수집했으면 빈 값을 돌려준다. 매물 유형은 지연 로딩이라 트랜잭션 안에서 꺼낸다.
     */
    private Optional<RegistryLookup> findUncollected(Long propertyId) {
        Property property = findProperty(propertyId);
        if (buildingRegistryRepository.existsByPropertyId(propertyId)) {
            return Optional.empty();
        }
        return Optional.of(lookupOf(property));
    }

    private Property findProperty(Long propertyId) {
        return propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
    }

    private static RegistryLookup lookupOf(Property property) {
        return new RegistryLookup(
                property.getPropertyId(),
                property.naturalKey(),
                property.getLandlordName(),
                PropertyType.valueOf(property.getPropertyTypeCode().getCodeValue()),
                property.getMarketPrice());
    }

    private RegistryRefreshOutcome replaceIfChanged(Long propertyId, RegistryDocument document) {
        Optional<BuildingRegistry> stored = buildingRegistryRepository.findByPropertyId(propertyId);
        if (stored.isEmpty()) {
            save(propertyId, document);
            return RegistryRefreshOutcome.COLLECTED;
        }

        BuildingRegistry registry = stored.get();
        List<OwnershipHistory> ownerships = ownershipHistoryRepository.findByRegistry(registry);
        List<MortgageHistory> mortgages = mortgageHistoryRepository.findByRegistry(registry);
        if (sameEntries(ownerships.stream().map(RegistryCommandService::toEntry).toList(), document.ownerships())
                && sameEntries(mortgages.stream().map(RegistryCommandService::toEntry).toList(),
                        document.mortgages())) {
            return RegistryRefreshOutcome.UNCHANGED;
        }

        // 일괄 삭제는 즉시 실행된다. 영속성 컨텍스트의 remove 는 flush 때 INSERT 뒤로 밀린다.
        ownershipHistoryRepository.deleteAllInBatch(ownerships);
        mortgageHistoryRepository.deleteAllInBatch(mortgages);
        saveHistories(registry, document);

        auditingHandler.markModified(registry);
        // 반영한 수정일시를 이벤트에 담는다. flush 때 감사 리스너가 한 번 더 채우므로 그 뒤의 값을 읽는다.
        buildingRegistryRepository.flush();
        eventPublisher.publishEvent(new RegistryChangedEvent(propertyId, registry.getUpdatedAt()));
        return RegistryRefreshOutcome.CHANGED;
    }

    /** 순서와 무관하게 같은 항목이 같은 수만큼 있는가. */
    private static <T> boolean sameEntries(List<T> stored, List<T> fetched) {
        return stored.size() == fetched.size() && counts(stored).equals(counts(fetched));
    }

    private static <T> Map<T, Long> counts(List<T> entries) {
        return entries.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static OwnershipEntry toEntry(OwnershipHistory history) {
        return new OwnershipEntry(history.getRankNo(), history.getRightType(), history.getOwnerName(),
                history.getOwnershipDate(), history.getRegistrationCause(), history.isCurrent());
    }

    /** 비어 있을 수 있는 금액 컬럼은 수집 형태와 같게 0 으로 본다 — 수집은 해당 없는 금액을 0 으로 담는다. */
    private static MortgageEntry toEntry(MortgageHistory history) {
        return new MortgageEntry(history.getPriorityNo(), history.getRightType(), history.getMortgageCreditor(),
                history.getDebtorName(), history.getReceiptDate(), history.getRegistrationCause(),
                Objects.requireNonNullElse(history.getMortgageAmount(), 0L), history.getMaxBondAmount(),
                Objects.requireNonNullElse(history.getPriorTenantDeposit(), 0L), history.isActive());
    }

    private void save(Long propertyId, RegistryDocument document) {
        // saveAndFlush — 제약 위반을 이 블록 안에서 드러내 이력 행을 넣기 전에 멈춘다.
        BuildingRegistry registry = buildingRegistryRepository.saveAndFlush(BuildingRegistry.collect(
                propertyId, document.buildingPurpose(), document.buildingStructure(), document.registryAddress(),
                document.exclusiveArea(), document.dataSource()));
        saveHistories(registry, document);
    }

    private void saveHistories(BuildingRegistry registry, RegistryDocument document) {
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
