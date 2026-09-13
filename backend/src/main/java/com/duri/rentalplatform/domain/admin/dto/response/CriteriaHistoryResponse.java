package com.duri.rentalplatform.domain.admin.dto.response;

import com.duri.rentalplatform.domain.admin.enums.CriteriaTarget;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 변경 이력 한 건 — API 명세서(관리자) 1.2. 매퍼가 직접 채운다. {@code changedBy} 는 탈퇴한 관리자면 null.
 * 시각은 서울 오프셋으로 바꾼다(공통 규약 1.1).
 */
public record CriteriaHistoryResponse(
        Long historyId,
        CriteriaTarget target,
        String targetKey,
        String field,
        String beforeValue,
        String afterValue,
        String changeReason,
        String changedBy,
        OffsetDateTime changedAt
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public CriteriaHistoryResponse {
        if (changedAt != null) {
            changedAt = changedAt.atZoneSameInstant(SEOUL).toOffsetDateTime();
        }
    }
}
