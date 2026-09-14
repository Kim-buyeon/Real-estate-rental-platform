package com.duri.rentalplatform.domain.risk.event;

import java.time.LocalDateTime;

/**
 * 다시 뗀 등기의 갑구 · 을구가 저장된 것과 달라 이력을 교체했다. 교체와 같은 쓰기 트랜잭션 안에서 발행하므로, 수신자는 커밋
 * 뒤에 반응하는 리스너로 붙는다. 처음 수집한 등기는 비교할 이전 내용이 없어 변동이 아니라 발행하지 않는다.
 *
 * @param propertyId 매물 ID
 * @param detectedAt 변동을 반영한 시각 — 표제부 수정일시(응답의 {@code collectedAt})와 같다. 서울 벽시계 시각이다
 */
public record RegistryChangedEvent(
        Long propertyId,
        LocalDateTime detectedAt
) {
}
