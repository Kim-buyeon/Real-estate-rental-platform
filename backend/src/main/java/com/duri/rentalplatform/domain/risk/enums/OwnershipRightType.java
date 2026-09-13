package com.duri.rentalplatform.domain.risk.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 갑구 등기 목적. {@code ownership_history.right_type} 에 저장하고 등기 이력 응답의 {@code rightType} 으로
 * 나간다 — API 명세서(위험도 분석) 1.3.
 *
 * <p>명세 예시가 보여주는 값은 {@link #OWNERSHIP_TRANSFER} 하나다. 나머지는 권리 침해 검출(RISK-03)이 찾는
 * 항목(압류 · 가압류 · 경매개시결정 · 신탁)과 경고 항목(가등기 · 임차권등기명령)을 갑구에 적기 위해 둔다.
 * {@link #PROVISIONAL_REGISTRATION} 은 명세 1.1 {@code warnings} 예시 값과 같은 이름으로 맞췄다.
 *
 * <p>임차권등기명령을 갑구에 두는 이유 — 데이터베이스 설계서 3장 8절이 그 여부 컬럼
 * ({@code lease_registration_yn})을 갑구 테이블에 두었다. 설계서를 따른다.
 */
@Getter
@RequiredArgsConstructor
public enum OwnershipRightType {
    OWNERSHIP_PRESERVATION("소유권보존"),
    OWNERSHIP_TRANSFER("소유권이전"),
    SEIZURE("압류"),
    PROVISIONAL_SEIZURE("가압류"),
    AUCTION_COMMENCEMENT("경매개시결정"),
    TRUST("신탁"),
    PROVISIONAL_REGISTRATION("가등기"),
    TENANCY_REGISTRATION_ORDER("임차권등기명령");

    private final String label;
}
