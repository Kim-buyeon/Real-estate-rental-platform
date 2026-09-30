package com.duri.rentalplatform.domain.property.service;

import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.entity.PropertyCode;
import com.duri.rentalplatform.domain.property.enums.CodeGroup;
import com.duri.rentalplatform.domain.property.enums.PropertyStatus;
import com.duri.rentalplatform.domain.property.repository.PropertyCodeRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.vo.MarketPriceUpdate;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.domain.property.vo.PropertyPriceSnapshot;
import com.duri.rentalplatform.domain.property.vo.PropertyRegistration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 적재의 저장 담당. <b>외부 호출이 한 줄도 없는 자리</b>다.
 *
 * <p>{@link PropertyLoadService} 와 나눈 이유가 이것이다. 외부 호출을 트랜잭션 안에 두면 제공처가
 * 느릴 때 DB 커넥션을 그 시간만큼 붙들고 있게 된다 — {@code backend/CLAUDE.md} Service 규칙.
 * 여기는 이미 다 만들어진 값을 받아 넣기만 한다.
 *
 * <p>코드 엔티티는 한 번 읽어 캐시한다. 매물마다 코드 테이블을 조회하면 건수만큼 왕복이 늘어난다.
 */
@Service
@RequiredArgsConstructor
public class PropertyLoadWriter {

    private final PropertyRepository propertyRepository;
    private final PropertyCodeRepository propertyCodeRepository;

    /** 코드 식별자만 캐시한다. 엔티티를 캐시하면 트랜잭션이 끝난 뒤 준영속 인스턴스를 재사용하게 된다. */
    private final Map<CodeKey, Long> codeIdCache = new HashMap<>();

    /**
     * 이미 적재된 매물의 자연키를 모은다. 중복 적재 차단의 비교 대상이다.
     */
    @Transactional(readOnly = true)
    public Set<PropertyNaturalKey> findLoadedNaturalKeys(String district) {
        return propertyRepository.findAllByDistrict(district).stream()
                .map(Property::naturalKey)
                .collect(Collectors.toSet());
    }

    /**
     * 이미 적재된 매물의 저장된 시세를 자연키로 모은다. 갱신 적재(RISK-08)가 새 시세와 견주는 비교 대상이다.
     *
     * <p>자연키가 같은 매물이 둘 이상이면 먼저 읽힌 쪽만 남긴다. 자연키 중복은 적재가 막으므로(자연키 주석) 정상 경로에서는
     * 생기지 않는다.
     */
    @Transactional(readOnly = true)
    public Map<PropertyNaturalKey, PropertyPriceSnapshot> findLoadedPrices(String district) {
        return propertyRepository.findAllByDistrict(district).stream()
                .collect(Collectors.toMap(
                        Property::naturalKey,
                        property -> new PropertyPriceSnapshot(property.getPropertyId(), property.getMarketPrice(),
                                property.getPriceType(), property.getPriceDate()),
                        (first, second) -> first));
    }

    /**
     * 한 덩어리를 저장하고 새 매물 식별자를 돌려준다. 이 메서드 하나가 트랜잭션 경계다.
     *
     * <p>식별자는 IDENTITY 라 저장 시점에 채워진다. 갱신 배치(RISK-08)가 새 매물을 판정 대상으로 넘기는 데 쓴다.
     */
    @Transactional
    public List<Long> saveAll(List<PropertyRegistration> registrations) {
        List<Property> properties = registrations.stream()
                .map(registration -> Property.register(
                        registration,
                        findCode(CodeGroup.CONTRACT_TYPE, registration.contractType().name()),
                        findCode(CodeGroup.PROPERTY_TYPE, registration.propertyType().name()),
                        findCode(CodeGroup.PROPERTY_STATUS, PropertyStatus.AVAILABLE.name())))
                .toList();

        propertyRepository.saveAll(properties);
        return properties.stream().map(Property::getPropertyId).toList();
    }

    /**
     * 기존 매물 한 덩어리의 시세를 갱신하고, 시세 금액이 바뀐 매물 식별자를 돌려준다. 이 메서드 하나가 트랜잭션 경계다.
     *
     * <p>값이 같은지는 엔티티가 한 번 더 본다 — 같으면 손대지 않으므로 UPDATE 도 나가지 않는다. 변경은 변경 감지가 반영한다.
     * 그 사이 지워진 매물은 조회되지 않아 건너뛴다.
     *
     * @return 시세 금액이 바뀐 매물 식별자. 기준일만 바뀐 매물은 들지 않는다
     */
    @Transactional
    public List<Long> updateMarketPrices(List<MarketPriceUpdate> updates) {
        Map<Long, MarketPriceUpdate> byId = updates.stream()
                .collect(Collectors.toMap(MarketPriceUpdate::propertyId, Function.identity(), (first, second) -> first));
        List<Long> priceChanged = new ArrayList<>();
        for (Property property : propertyRepository.findAllById(byId.keySet())) {
            MarketPriceUpdate update = byId.get(property.getPropertyId());
            if (property.refreshMarketPrice(update.marketPrice(), update.priceType(), update.priceDate())) {
                priceChanged.add(property.getPropertyId());
            }
        }
        return priceChanged;
    }

    /**
     * 코드 값을 엔티티 참조로 바꾼다.
     *
     * <p>없으면 예외다. 기본 코드를 대신 넣지 않는다 — 시드가 빠진 채로 적재가 돌면 전 매물이 엉뚱한
     * 유형으로 저장되고, 그 사실은 화면을 열어야 드러난다.
     *
     * <p>여기서만 {@code BusinessException} 을 쓰지 않는다. 시드 누락은 업무 규칙 위반이 아니라 기동
     * 구성의 문제이고, 이 경로는 API 응답으로 이어지지 않아 대응되는 오류 코드가 「API 명세서 —
     * 공통 규약」 2장에 없다. 비슷한 코드를 끌어다 쓰면 명세에 없는 뜻이 생긴다.
     */
    private PropertyCode findCode(CodeGroup codeGroup, String codeValue) {
        Long codeId = codeIdCache.computeIfAbsent(new CodeKey(codeGroup, codeValue), key ->
                propertyCodeRepository.findByCodeGroupAndCodeValue(key.codeGroup(), key.codeValue())
                        .map(PropertyCode::getCodeId)
                        .orElseThrow(() -> new IllegalStateException(
                                "공통 코드 시드가 없다: %s / %s".formatted(key.codeGroup(), key.codeValue()))));
        return propertyCodeRepository.getReferenceById(codeId);
    }

    private record CodeKey(CodeGroup codeGroup, String codeValue) {
    }
}
