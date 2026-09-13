package com.duri.rentalplatform.domain.risk.calculator;

import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.vo.OwnershipRightEntry;
import com.duri.rentalplatform.domain.risk.vo.RightViolationResult;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 갑구 이력에서 권리 침해와 경고 항목을 찾는다(RISK-03).
 *
 * <p>말소된 행({@code current = false})은 보지 않는다. 남은 행을 {@link OwnershipRightType#getCategory()}
 * 로 가르고, 같은 목적이 여러 건이어도 한 번만 담는다. 목록 순서는 열거형 선언 순이다.
 *
 * <p>기준 테이블 값이 없는 존재 여부 판정이라 임계값 인자를 받지 않는다.
 */
public final class RightViolationDetector {

    public static RightViolationResult detect(List<OwnershipRightEntry> entries) {
        Set<OwnershipRightType> violations = EnumSet.noneOf(OwnershipRightType.class);
        Set<OwnershipRightType> warnings = EnumSet.noneOf(OwnershipRightType.class);

        for (OwnershipRightEntry entry : entries) {
            if (!entry.current()) {
                continue;
            }
            OwnershipRightType type = entry.rightType();
            // switch 식이라 default 없이 모든 분류를 다뤄야 컴파일된다. 분류가 늘면 여기서 멈춘다.
            // 식은 담을 목록을 고르기만 하고, 담는 것은 아래 한 곳에서 한다. 해당 없음은 담을 곳이 없다.
            Set<OwnershipRightType> bucket = switch (type.getCategory()) {
                case VIOLATION -> violations;
                case WARNING -> warnings;
                case NONE -> null;
            };
            if (bucket != null) {
                bucket.add(type);
            }
        }
        return new RightViolationResult(List.copyOf(violations), List.copyOf(warnings));
    }

    private RightViolationDetector() {
    }
}
