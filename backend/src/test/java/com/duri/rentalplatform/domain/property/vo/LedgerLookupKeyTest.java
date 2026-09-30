package com.duri.rentalplatform.domain.property.vo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link LedgerLookupKey#of} 검증. 형식 밖이면 추측하지 않고 빈 값이다 — 추측한 키는 다른 건물의 대장을 떼어 온다.
 */
class LedgerLookupKeyTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void parse(String caseName, String sigunguCode, String legalDongCode, String jibun,
            Optional<LedgerLookupKey> expected) {
        assertThat(LedgerLookupKey.of(sigunguCode, legalDongCode, jibun)).isEqualTo(expected);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("본번-부번 200-16 → 0200 · 0016", "11110", "1111017400", "200-16",
                        Optional.of(new LedgerLookupKey("11110", "17400", "0200", "0016"))),
                Arguments.of("본번만 702 → 0702 · 0000", "11110", "1111017400", "702",
                        Optional.of(new LedgerLookupKey("11110", "17400", "0702", "0000"))),
                Arguments.of("네 자리 본번 · 부번 1234-5678", "11110", "1111017400", "1234-5678",
                        Optional.of(new LedgerLookupKey("11110", "17400", "1234", "5678"))),
                Arguments.of("앞뒤 공백은 걷는다", " 11110 ", " 1111017400 ", " 702 ",
                        Optional.of(new LedgerLookupKey("11110", "17400", "0702", "0000"))),
                Arguments.of("산 지번 — 형식 밖", "11110", "1111017400", "산12", Optional.empty()),
                Arguments.of("블록 · 로트 표기 — 형식 밖", "11110", "1111017400", "B1-2", Optional.empty()),
                Arguments.of("세 마디 — 형식 밖", "11110", "1111017400", "1-2-3", Optional.empty()),
                Arguments.of("다섯 자리 본번 — 형식 밖", "11110", "1111017400", "12345", Optional.empty()),
                Arguments.of("빈 지번", "11110", "1111017400", " ", Optional.empty()),
                Arguments.of("지번 없음", "11110", "1111017400", null, Optional.empty()),
                Arguments.of("법정동 코드 없음(Mock 정규화)", "11110", null, "702", Optional.empty()),
                Arguments.of("법정동 코드 자릿수 틀림", "11110", "11110174", "702", Optional.empty()),
                Arguments.of("법정동 코드 앞 5자리가 시군구 코드와 다름 — 두 응답이 다른 곳", "11110", "1168010100", "702",
                        Optional.empty()),
                Arguments.of("시군구 코드 자릿수 틀림", "1111", "1111017400", "702", Optional.empty())
        );
    }
}
