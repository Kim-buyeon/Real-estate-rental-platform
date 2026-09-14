package com.duri.rentalplatform.domain.risk.event;

import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import java.time.LocalDateTime;

/**
 * 매물의 위험 등급이 직전 최신 분석과 달라졌다. 분석 기록 지점 한 곳에서 쓰기 트랜잭션 안에 발행하므로, 수신자는 커밋 뒤에
 * 반응하는 리스너로 붙는다 — 되돌려진 분석으로 알림이 나가지 않는다. 첫 분석(직전 등급 없음)은 변경이 아니라 발행하지 않는다.
 *
 * @param propertyId    매물 ID
 * @param previousGrade 직전 최신 분석의 등급
 * @param riskGrade     새 최신 분석의 등급
 * @param analyzedAt    새 최신 분석 행의 시각. DB 와 같은 서울 벽시계 시각이다
 */
public record RiskGradeChangedEvent(
        Long propertyId,
        RiskGrade previousGrade,
        RiskGrade riskGrade,
        LocalDateTime analyzedAt
) {
}
