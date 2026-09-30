package com.duri.rentalplatform.domain.property.entity;

import com.duri.rentalplatform.common.CreatedAtEntity;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.vo.LedgerLookupKey;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.domain.property.vo.PropertyRegistration;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매물. 국토교통부 전월세 실거래가에서 적재하며, 사용자나 관리자가 등록하는 경로는 없다 —
 * 데이터 적재 설계서 1.4.
 *
 * <p><b>감사 상위 클래스</b> — {@code property} 에는 {@code updated_at} 이 없고, 생성 시각을 담는
 * 컬럼 이름이 {@code created_at} 이 아니라 {@code registered_at} 이다(데이터베이스 설계서 3장
 * 4절). 뜻은 같고 이름만 다르므로 {@link CreatedAtEntity} 를 상속하고
 * {@code @AttributeOverride} 로 컬럼명을 맞춘다. 시각은 감사 리스너가 채우며, 리스너는 상위
 * 클래스에 붙어 있어 여기에 다시 붙이지 않는다. 적재 시점을 함께 저장한다는 요구는
 * 데이터 적재 설계서 1.4 에 있다.
 *
 * <p><b>금액을 {@code Long} 으로 두는 이유</b> — {@code backend/CLAUDE.md} 는 「금액 · 이율은
 * BigDecimal」이라고 적지만 스키마는 {@code deposit} · {@code monthly_rent} · {@code market_price} 를
 * 모두 BIGINT 로 정했다. BigDecimal 로 매핑하면 Hibernate 가 NUMERIC 을 기대해
 * {@code ddl-auto: validate} 에서 타입이 어긋난다. 스키마를 따르고 <b>문서와의 어긋남은 그대로
 * 둔다</b> — 이미 알려진 미해결 문서 이슈이며 이 변경에서 고치지 않는다. 원 단위 정수라 Long 으로
 * 정확히 표현된다. 나눗셈과 이율이 들어가는 판정 · 한도 계산은 BigDecimal 로 한다.
 */
@Entity
@Getter
@Table(name = "property")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "createdAt", column = @Column(name = "registered_at", updatable = false))
public class Property extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long propertyId;

    @Column(nullable = false, length = 200)
    private String address;

    @Column(nullable = false, length = 30)
    private String district;

    /** 계약 상대방. 등기상 소유자와의 일치 검증(RISK-04)에 쓴다. */
    @Column(nullable = false, length = 50)
    private String landlordName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_type_code_id", nullable = false)
    private PropertyCode contractTypeCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_type_code_id", nullable = false)
    private PropertyCode propertyTypeCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "status_code_id", nullable = false)
    private PropertyCode statusCode;

    @Column(nullable = false)
    private Long deposit;

    private Long monthlyRent;

    /**
     * 시세(주택가액). 깡통전세 판정(RISK-02) 기준금액의 밑값이자, 전세가율
     * ({@code debtRatio} = (선순위채권 + 보증금) ÷ 주택가액)의 분모다 — 비즈니스 로직
     * 정의서 2장 · 4장.
     */
    @Column(nullable = false)
    private Long marketPrice;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PriceType priceType;

    private LocalDate priceDate;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal areaSqm;

    private Integer floor;

    private Integer builtYear;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    // 건축물대장 조회 키(V17). 넷이 한 묶음이라 전부 있거나 전부 없다 — 테이블 CHECK 제약이 막고, 여기서도 한 묶음으로만
    // 넣는다(register · fillLedgerKey). 읽을 때는 ledgerKey() 로 묶어서 꺼낸다.

    @Column(length = 5)
    private String sigunguCode;

    @Column(length = 5)
    private String bjdongCode;

    @Column(length = 4)
    private String bun;

    @Column(length = 4)
    private String ji;

    /**
     * 적재된 실거래 한 건을 매물로 만든다.
     *
     * <p>코드 세 개는 {@code property_code} 에서 찾아 넘긴다. 값이 아니라 엔티티를 받는 이유는
     * FK 가 셋 다 NOT NULL 이라서다 — 코드가 없는 상태를 만들 수 없게 한다.
     */
    public static Property register(
            PropertyRegistration registration,
            PropertyCode contractTypeCode,
            PropertyCode propertyTypeCode,
            PropertyCode statusCode) {

        Property property = new Property();
        property.address = registration.address();
        property.district = registration.district();
        property.landlordName = registration.landlordName();
        property.contractTypeCode = contractTypeCode;
        property.propertyTypeCode = propertyTypeCode;
        property.statusCode = statusCode;
        property.deposit = registration.deposit();
        property.monthlyRent = registration.monthlyRent();
        property.marketPrice = registration.marketPrice();
        property.priceType = registration.priceType();
        property.priceDate = registration.priceDate();
        property.areaSqm = registration.areaSqm();
        property.floor = registration.floor();
        property.builtYear = registration.builtYear();
        property.latitude = registration.latitude();
        property.longitude = registration.longitude();
        property.applyLedgerKey(registration.ledgerKey());
        return property;
    }

    /**
     * 비어 있는 건축물대장 조회 키를 채운다. 이 키가 생기기 전에 적재된 매물을 갱신 배치(RISK-08)가 이행하는 자리다.
     * 이미 키가 있으면 손대지 않는다 — 같은 자연키의 매물은 같은 지번이라 덮어쓸 이유가 없고, 덮어쓰면 이미 수집한 대장과
     * 키가 어긋날 수 있다.
     *
     * @return 채웠는가
     */
    public boolean fillLedgerKey(LedgerLookupKey key) {
        if (key == null || sigunguCode != null) {
            return false;
        }
        applyLedgerKey(key);
        return true;
    }

    /** 건축물대장 조회 키. 없으면 null. */
    public LedgerLookupKey ledgerKey() {
        if (sigunguCode == null) {
            return null;
        }
        return new LedgerLookupKey(sigunguCode, bjdongCode, bun, ji);
    }

    private void applyLedgerKey(LedgerLookupKey key) {
        if (key == null) {
            return;
        }
        sigunguCode = key.sigunguCode();
        bjdongCode = key.bjdongCode();
        bun = key.bun();
        ji = key.ji();
    }

    /**
     * 갱신 배치(RISK-08)가 새로 계산한 시세로 바꾼다. 금액 · 산출 근거 · 기준일이 모두 저장값과 같으면 손대지 않는다 —
     * 데이터 적재 설계서 1.5 「값이 동일하면 재분석하지 않는다」.
     *
     * <p>기준일만 바뀌어도 저장한다. 화면이 시세와 함께 기준일을 표시하기 때문이다(같은 설계서 1.4). 다만 판정에 쓰이는 값은
     * 금액뿐이므로 <b>금액이 바뀐 경우만 참</b>을 돌려 재분석 대상으로 표시하게 한다.
     *
     * @return 시세 금액이 바뀌었는가. 참이면 위험도 재분석 대상이다
     */
    public boolean refreshMarketPrice(Long newMarketPrice, PriceType newPriceType, LocalDate newPriceDate) {
        boolean amountChanged = !Objects.equals(marketPrice, newMarketPrice);
        if (!amountChanged && priceType == newPriceType && Objects.equals(priceDate, newPriceDate)) {
            return false;
        }
        marketPrice = newMarketPrice;
        priceType = newPriceType;
        priceDate = newPriceDate;
        return amountChanged;
    }

    /** 중복 적재 차단에 쓰는 자연키. */
    public PropertyNaturalKey naturalKey() {
        return new PropertyNaturalKey(address, areaSqm, floor, deposit, monthlyRent);
    }
}
