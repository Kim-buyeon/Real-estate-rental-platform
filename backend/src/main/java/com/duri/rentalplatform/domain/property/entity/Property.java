package com.duri.rentalplatform.domain.property.entity;

import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.domain.property.vo.PropertyRegistration;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 매물. 국토교통부 전월세 실거래가에서 적재하며, 사용자나 관리자가 등록하는 경로는 없다 —
 * 데이터 적재 설계서 1.4.
 *
 * <p><b>감사 상위 클래스를 상속하지 않는 이유</b> — 규칙은 「{@code updated_at} 이 있으면 BaseEntity,
 * 없으면 CreatedAtEntity」다. 그런데 {@code property} 에는 {@code updated_at} 도 {@code created_at} 도
 * 없고 {@code registered_at} 이 그 자리를 대신한다(데이터베이스 설계서 3장 4절). {@code CreatedAtEntity}
 * 를 상속하면 스키마에 없는 {@code created_at} 이 매핑에 생겨 {@code ddl-auto: validate} 가 기동을
 * 막는다. 그래서 상속 대신 <b>같은 감사 리스너를 이 엔티티에 직접 붙여</b> {@code registered_at} 을
 * 채운다. 규칙이 지키려는 것(시각을 코드에서 넣지 않는다)은 그대로 유지된다.
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
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Property {

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

    /** 시세. RISK-02 전세가율의 분모다. */
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

    /** 적재 시점. 감사 리스너가 채운다 — 데이터 적재 설계서 1.4 「적재 시점을 함께 저장한다」. */
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime registeredAt;

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
        return property;
    }

    /** 중복 적재 차단에 쓰는 자연키. */
    public PropertyNaturalKey naturalKey() {
        return new PropertyNaturalKey(address, areaSqm, floor, deposit, monthlyRent);
    }
}
