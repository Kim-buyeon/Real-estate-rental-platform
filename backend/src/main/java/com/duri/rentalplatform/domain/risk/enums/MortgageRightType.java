package com.duri.rentalplatform.domain.risk.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 을구 등기 목적. {@code mortgage_history.right_type} 에 저장한다. 등기 이력 응답에는 나가지 않는다 —
 * 명세 1.3 의 {@code mortgages[]} 에 목적 필드가 없다.
 *
 * <p>데이터베이스 설계서 3장 9절은 MORTGAGE · LEASE · TENANCY · ATTACHMENT 를 예로 든다. 지금 수집 경로가
 * 만드는 것은 근저당과 선순위 임차인 둘뿐이라 그 둘만 둔다. 나머지는 만드는 경로가 생길 때 더한다.
 */
@Getter
@RequiredArgsConstructor
public enum MortgageRightType {
    MORTGAGE("근저당권"),
    TENANCY("임차권");

    private final String label;
}
