package com.duri.rentalplatform.domain.risk.calculator;

import static com.duri.rentalplatform.domain.risk.enums.OwnershipRightType.AUCTION_COMMENCEMENT;
import static com.duri.rentalplatform.domain.risk.enums.OwnershipRightType.OWNERSHIP_PRESERVATION;
import static com.duri.rentalplatform.domain.risk.enums.OwnershipRightType.OWNERSHIP_TRANSFER;
import static com.duri.rentalplatform.domain.risk.enums.OwnershipRightType.PROVISIONAL_REGISTRATION;
import static com.duri.rentalplatform.domain.risk.enums.OwnershipRightType.PROVISIONAL_SEIZURE;
import static com.duri.rentalplatform.domain.risk.enums.OwnershipRightType.SEIZURE;
import static com.duri.rentalplatform.domain.risk.enums.OwnershipRightType.TENANCY_REGISTRATION_ORDER;
import static com.duri.rentalplatform.domain.risk.enums.OwnershipRightType.TRUST;
import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.vo.OwnershipRightEntry;
import com.duri.rentalplatform.domain.risk.vo.RightViolationResult;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link RightViolationDetector} 판정 검증. 기대값 표는 이슈 #45 계획의 13행이고, 한 행이 한 케이스다.
 */
class RightViolationDetectorTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void detect(String caseName, List<OwnershipRightEntry> entries,
            List<OwnershipRightType> expectedViolations, List<OwnershipRightType> expectedWarnings) {
        RightViolationResult result = RightViolationDetector.detect(entries);

        assertThat(result.rightViolations()).containsExactlyElementsOf(expectedViolations);
        assertThat(result.warnings()).containsExactlyElementsOf(expectedWarnings);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("RISK-03-01 갑구 0건 — 없음",
                        List.of(),
                        List.of(), List.of()),
                Arguments.of("RISK-03-02 소유권보존·소유권이전 유효 — 침해 아님",
                        List.of(valid(OWNERSHIP_PRESERVATION), valid(OWNERSHIP_TRANSFER)),
                        List.of(), List.of()),
                Arguments.of("RISK-03-03 압류 유효 — 침해",
                        List.of(valid(SEIZURE)),
                        List.of(SEIZURE), List.of()),
                Arguments.of("RISK-03-04 가압류 유효 — 침해",
                        List.of(valid(PROVISIONAL_SEIZURE)),
                        List.of(PROVISIONAL_SEIZURE), List.of()),
                Arguments.of("RISK-03-05 경매개시결정 유효 — 침해",
                        List.of(valid(AUCTION_COMMENCEMENT)),
                        List.of(AUCTION_COMMENCEMENT), List.of()),
                Arguments.of("RISK-03-06 신탁 유효 — 침해",
                        List.of(valid(TRUST)),
                        List.of(TRUST), List.of()),
                Arguments.of("RISK-03-07 압류 말소 — 제외",
                        List.of(cancelled(SEIZURE)),
                        List.of(), List.of()),
                Arguments.of("RISK-03-08 가등기 유효 — 경고",
                        List.of(valid(PROVISIONAL_REGISTRATION)),
                        List.of(), List.of(PROVISIONAL_REGISTRATION)),
                Arguments.of("RISK-03-09 임차권등기명령 유효 — 경고",
                        List.of(valid(TENANCY_REGISTRATION_ORDER)),
                        List.of(), List.of(TENANCY_REGISTRATION_ORDER)),
                Arguments.of("RISK-03-10 가등기 말소 — 제외",
                        List.of(cancelled(PROVISIONAL_REGISTRATION)),
                        List.of(), List.of()),
                Arguments.of("RISK-03-11 압류 유효 2건 — 같은 목적은 한 번",
                        List.of(valid(SEIZURE), valid(SEIZURE)),
                        List.of(SEIZURE), List.of()),
                Arguments.of("RISK-03-12 신탁·가압류·가등기·소유권이전 유효 — 혼합, 열거형 선언 순",
                        List.of(valid(TRUST), valid(PROVISIONAL_SEIZURE), valid(PROVISIONAL_REGISTRATION),
                                valid(OWNERSHIP_TRANSFER)),
                        List.of(PROVISIONAL_SEIZURE, TRUST), List.of(PROVISIONAL_REGISTRATION)),
                Arguments.of("RISK-03-13 압류 말소·가압류 유효 — 말소·유효 혼합",
                        List.of(cancelled(SEIZURE), valid(PROVISIONAL_SEIZURE)),
                        List.of(PROVISIONAL_SEIZURE), List.of())
        );
    }

    /** 순위번호 · 권리자는 검출에 쓰이지 않는다. 고정값을 넣는다. */
    private static OwnershipRightEntry valid(OwnershipRightType type) {
        return new OwnershipRightEntry(1, type, "권리자", true);
    }

    private static OwnershipRightEntry cancelled(OwnershipRightType type) {
        return new OwnershipRightEntry(1, type, "권리자", false);
    }
}
