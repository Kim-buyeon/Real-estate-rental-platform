package com.duri.rentalplatform.domain.risk.calculator;

import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.vo.ConsistencyInput;
import com.duri.rentalplatform.domain.risk.vo.ConsistencyResult;
import com.duri.rentalplatform.domain.risk.vo.OwnershipRightEntry;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 등기 · 건축물대장 · 매물의 명의와 문서가 서로 맞는지 대조한다(RISK-04).
 *
 * <ul>
 *   <li><b>현재 소유자</b> — 목적이 소유권보존 · 소유권이전이고 말소되지 않은 행 중 순위번호가 가장 큰 행의 권리자.
 *       순위번호가 등기 순서다(접수일은 같은 날이 있을 수 있다). 그런 행이 없으면 명의 불일치다.</li>
 *   <li><b>이름 · 주소</b> — 앞뒤 공백을 떼고 연속 공백을 한 칸으로 접은 뒤 완전 일치. 유사도를 쓰지 않는다 — 번지 하나
 *       차이가 곧 다른 집이다. 도로명 · 지번 변환은 적재 단계의 정규화가 맡는다.</li>
 *   <li><b>면적</b> — 소수 자릿수와 무관한 값 비교({@code compareTo}). 허용 오차의 근거 문서가 없어 두지 않는다.
 *       어느 한쪽이 없으면 불일치다.</li>
 * </ul>
 *
 * <p>기준 테이블 값이 없는 대조라 임계값 인자를 받지 않는다.
 */
public final class DocumentConsistencyChecker {

    private static final Pattern WHITESPACES = Pattern.compile("\\s+");

    public static ConsistencyResult check(ConsistencyInput input) {
        boolean ownerNameMatched = currentOwner(input)
                .map(owner -> sameName(owner.holderName(), input.landlordName()))
                .orElse(false);
        return new ConsistencyResult(
                ownerNameMatched,
                sameAddress(input.ledgerAddress(), input.registryAddress()),
                input.violationBuilding(),
                sameArea(input.ledgerExclusiveArea(), input.registryExclusiveArea()));
    }

    private static Optional<OwnershipRightEntry> currentOwner(ConsistencyInput input) {
        return input.ownerships().stream()
                .filter(OwnershipRightEntry::current)
                .filter(entry -> entry.rightType() == OwnershipRightType.OWNERSHIP_PRESERVATION
                        || entry.rightType() == OwnershipRightType.OWNERSHIP_TRANSFER)
                .max(Comparator.comparingInt(OwnershipRightEntry::rankNo));
    }

    /** 이름은 앞뒤 공백만 걷는다. 이름 안의 공백은 다른 사람을 가를 수 있다. */
    private static boolean sameName(String left, String right) {
        return left != null && right != null && left.strip().equals(right.strip());
    }

    /** 주소는 앞뒤 공백을 걷고 연속 공백을 한 칸으로 접는다. 번지 · 동 표기는 적재 단계 정규화가 맡는다. */
    private static boolean sameAddress(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return collapse(left).equals(collapse(right));
    }

    private static String collapse(String text) {
        return WHITESPACES.matcher(text.strip()).replaceAll(" ");
    }

    private static boolean sameArea(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private DocumentConsistencyChecker() {
    }
}
