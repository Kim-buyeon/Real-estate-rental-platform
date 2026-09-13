package com.duri.rentalplatform.domain.admin.dto.condition;

import java.time.LocalDateTime;

/**
 * 이력 커서 조회 조건. 커서 문자열을 마지막 행의 변경 시각(서울 벽시계) · 식별자로 푼 값이다. 첫 페이지는 둘 다 null.
 * {@code limit} 은 다음 페이지 판정을 위해 요청 크기 + 1.
 */
public record CriteriaHistoryCondition(
        LocalDateTime lastChangedAt,
        Long lastId,
        int limit
) {
}
