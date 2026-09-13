package com.duri.rentalplatform.domain.risk.calculator;

import static com.duri.rentalplatform.domain.risk.enums.OwnershipRightType.OWNERSHIP_PRESERVATION;
import static com.duri.rentalplatform.domain.risk.enums.OwnershipRightType.OWNERSHIP_TRANSFER;
import static com.duri.rentalplatform.domain.risk.enums.OwnershipRightType.SEIZURE;
import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.vo.ConsistencyInput;
import com.duri.rentalplatform.domain.risk.vo.ConsistencyResult;
import com.duri.rentalplatform.domain.risk.vo.OwnershipRightEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link DocumentConsistencyChecker} 대조 검증. 기대값 표는 이슈 #53 계획의 12행이고, 한 행이 한 케이스다.
 *
 * <p>공통 입력 — 임대인 「김임대」, 대장 주소 「서울특별시 강남구 역삼동 123-4」, 대장 전용면적 42.50, 위반 아님.
 * 케이스마다 다른 값만 바꾼다.
 */
class DocumentConsistencyCheckerTest {

    private static final String LANDLORD = "김임대";
    private static final String LEDGER_ADDRESS = "서울특별시 강남구 역삼동 123-4";
    private static final BigDecimal LEDGER_AREA = new BigDecimal("42.50");
    private static final List<OwnershipRightEntry> OWNED_BY_LANDLORD =
            List.of(valid(1, OWNERSHIP_PRESERVATION, LANDLORD));

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void check(String caseName, ConsistencyInput input, ConsistencyResult expected) {
        assertThat(DocumentConsistencyChecker.check(input)).isEqualTo(expected);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("RISK-04-01 모두 일치",
                        input(OWNED_BY_LANDLORD, LEDGER_ADDRESS, LEDGER_AREA, false),
                        result(true, true, false, true)),
                Arguments.of("RISK-04-02 보존 말소 + 이전 유효 — 현재 소유자는 유효한 최신 소유권",
                        input(List.of(cancelled(1, OWNERSHIP_PRESERVATION, "이전주인"),
                                valid(2, OWNERSHIP_TRANSFER, LANDLORD)), LEDGER_ADDRESS, LEDGER_AREA, false),
                        result(true, true, false, true)),
                Arguments.of("RISK-04-03 이전 유효 박소유 — 명의 불일치",
                        input(List.of(valid(1, OWNERSHIP_TRANSFER, "박소유")), LEDGER_ADDRESS, LEDGER_AREA, false),
                        result(false, true, false, true)),
                Arguments.of("RISK-04-04 소유자명 앞뒤 공백 — 공백 제거",
                        input(List.of(valid(1, OWNERSHIP_TRANSFER, " 김임대 ")), LEDGER_ADDRESS, LEDGER_AREA, false),
                        result(true, true, false, true)),
                Arguments.of("RISK-04-05 보존 김임대 + 압류 국세청 순위3 — 권리 등기는 소유자가 아니다",
                        input(List.of(valid(1, OWNERSHIP_PRESERVATION, LANDLORD), valid(3, SEIZURE, "국세청")),
                                LEDGER_ADDRESS, LEDGER_AREA, false),
                        result(true, true, false, true)),
                Arguments.of("RISK-04-06 보존 말소만 — 유효한 소유권 없음",
                        input(List.of(cancelled(1, OWNERSHIP_PRESERVATION, LANDLORD)), LEDGER_ADDRESS, LEDGER_AREA,
                                false),
                        result(false, true, false, true)),
                Arguments.of("RISK-04-07 등기 주소 연속 공백 — 접어서 일치",
                        input(OWNED_BY_LANDLORD, "서울특별시  강남구 역삼동 123-4", LEDGER_AREA, false),
                        result(true, true, false, true)),
                Arguments.of("RISK-04-08 등기 주소 123-5 — 주소 불일치",
                        input(OWNED_BY_LANDLORD, "서울특별시 강남구 역삼동 123-5", LEDGER_AREA, false),
                        result(true, false, false, true)),
                Arguments.of("RISK-04-09 대장 위반 — 위반건축물",
                        input(OWNED_BY_LANDLORD, LEDGER_ADDRESS, LEDGER_AREA, true),
                        result(true, true, true, true)),
                Arguments.of("RISK-04-10 등기 면적 42.51 — 면적 +0.01 불일치",
                        input(OWNED_BY_LANDLORD, LEDGER_ADDRESS, new BigDecimal("42.51"), false),
                        result(true, true, false, false)),
                Arguments.of("RISK-04-11 등기 면적 42.5(scale 1) — scale 무관 일치",
                        input(OWNED_BY_LANDLORD, LEDGER_ADDRESS, new BigDecimal("42.5"), false),
                        result(true, true, false, true)),
                Arguments.of("RISK-04-12 등기 면적 NULL — 불일치",
                        input(OWNED_BY_LANDLORD, LEDGER_ADDRESS, null, false),
                        result(true, true, false, false)),
                Arguments.of("RISK-04-13 소유자명 안쪽 공백 — 이름은 접지 않아 불일치",
                        input(List.of(valid(1, OWNERSHIP_TRANSFER, "김 임대")), LEDGER_ADDRESS, LEDGER_AREA, false),
                        result(false, true, false, true))
        );
    }

    private static ConsistencyInput input(List<OwnershipRightEntry> ownerships, String registryAddress,
            BigDecimal registryArea, boolean violation) {
        return new ConsistencyInput(ownerships, LANDLORD, LEDGER_ADDRESS, registryAddress, LEDGER_AREA, registryArea,
                violation);
    }

    private static ConsistencyResult result(boolean owner, boolean address, boolean violation, boolean area) {
        return new ConsistencyResult(owner, address, violation, area);
    }

    private static OwnershipRightEntry valid(int rankNo, OwnershipRightType type, String holder) {
        return new OwnershipRightEntry(rankNo, type, holder, true);
    }

    private static OwnershipRightEntry cancelled(int rankNo, OwnershipRightType type, String holder) {
        return new OwnershipRightEntry(rankNo, type, holder, false);
    }
}
