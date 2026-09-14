package com.duri.rentalplatform.domain.risk.event;

import java.time.LocalDateTime;

/**
 * 다시 뗀 등기의 갑구 · 을구가 저장된 것과 달라 이력을 교체했다. 교체와 같은 쓰기 트랜잭션 안에서 발행하므로, 수신자는 커밋
 * 뒤에 반응하는 리스너로 붙는다. 처음 수집한 등기는 비교할 이전 내용이 없어 변동이 아니라 발행하지 않는다.
 *
 * <p><b>요약</b> — 변동 전 · 후를 갑구 · 을구의 유효 건수와 내용 지문으로 담는다(예: {@code 갑구 2 · 을구 1 · a1b2c3d4}).
 * 관심 매물 알림의 변동 전 · 후 값(데이터베이스 설계서 29절, 100자)이 된다. 형식과 지문을 붙이는 이유는
 * {@link com.duri.rentalplatform.domain.risk.calculator.RegistrySummaryCalculator}.
 *
 * @param propertyId    매물 ID
 * @param beforeSummary 교체 전 저장돼 있던 갑구 · 을구 요약
 * @param afterSummary  새로 반영한 갑구 · 을구 요약
 * @param detectedAt    변동을 반영한 시각 — 표제부 수정일시(응답의 {@code collectedAt})와 같다. 서울 벽시계 시각이다
 */
public record RegistryChangedEvent(
        Long propertyId,
        String beforeSummary,
        String afterSummary,
        LocalDateTime detectedAt
) {
}
