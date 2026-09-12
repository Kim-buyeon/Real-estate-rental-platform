package com.duri.rentalplatform.domain.property.entity;

import com.duri.rentalplatform.domain.property.enums.CodeGroup;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 유형 · 매물 유형 · 매물 상태 공통 코드.
 *
 * <p>값은 시드 마이그레이션({@code V2__seed_property_code.sql})이 넣고 애플리케이션은 읽기만 한다.
 * 그래서 생성 팩토리를 두지 않는다 — 코드 값을 코드에서 만들 수 있게 하면 마이그레이션과 런타임 중
 * 어느 쪽이 진실인지 흐려진다.
 *
 * <p><b>감사 상위 클래스를 상속하지 않는다.</b> {@code property_code} 에는 {@code created_at} 도
 * {@code updated_at} 도 없다(데이터베이스 설계서 3장 1절). 상속하면 매핑에만 있는 컬럼이 생겨
 * {@code ddl-auto: validate} 에서 기동이 실패한다.
 */
@Entity
@Getter
@Table(name = "property_code")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long codeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CodeGroup codeGroup;

    @Column(nullable = false, length = 30)
    private String codeValue;

    @Column(nullable = false, length = 50)
    private String codeName;

    @Column(length = 200)
    private String codeDescription;

    @Column(nullable = false)
    private Integer sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}
