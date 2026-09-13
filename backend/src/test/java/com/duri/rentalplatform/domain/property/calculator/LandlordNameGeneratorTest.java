package com.duri.rentalplatform.domain.property.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LandlordNameGenerator} 검증.
 *
 * <p>핵심은 <b>재현성</b>과 <b>독립성</b> 둘이다 — 클래스 Javadoc.
 *
 * <ul>
 *   <li>재현성 — 같은 자연키는 재실행해도 같은 이름이어야 적재를 다시 돌려도 임대인이 바뀌지 않는다.</li>
 *   <li>독립성 — {@link LandlordNameGenerator#generate}(이름 선택)와
 *       {@link LandlordNameGenerator#isIntentionalMismatch}(등기 불일치 여부)는 서로 다른 씨앗을 더한
 *       뒤 눈사태(avalanche) 섞기 함수(FNV-1a로 자연키를 접고 murmur3 finalizer 로 마무리)를 거쳐
 *       파생된다. 단순히 씨앗만 다르면 부족하다 — 클래스 Javadoc이 적듯 {@code String.hashCode} 같은
 *       선형 해시는 씨앗이 달라도 두 결과 사이에 <b>상수 차이</b>가 남는다. 눈사태 단계가 그 상수
 *       차이를 전 비트로 흩어 놓아야 두 값이 서로에게서 예측되지 않는다. 두 값이 사실상 같은 근원에서
 *       나오면(고정 관계가 남으면) RISK-04 명의 정합 판정이 <b>언제나 일치</b>로 나와 검증이 죽는다
 *       — 그래서 "같은 이름을 고른 매물들이 불일치 여부에서는 갈린다"와 "두 파생값 사이에 고정된 상수
 *       차이가 없다"를 직접 확인한다.</li>
 * </ul>
 */
class LandlordNameGeneratorTest {

    @Test
    @DisplayName("같은 자연키는 항상 같은 임대인명을 낸다")
    void generatesTheSameNameForTheSameNaturalKeyEveryTime() {
        PropertyNaturalKey naturalKey = naturalKeyOf("서울특별시 강남구 역삼동 100-1");

        String first = LandlordNameGenerator.generate(naturalKey);
        String second = LandlordNameGenerator.generate(naturalKey);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("같은 이름으로 뽑힌 매물들 사이에서도 등기 불일치 여부는 갈린다 — 이름 선택과 독립된 파생이다")
    void mismatchFlagIsNotDeterminedByTheGeneratedName() {
        // 주소만 바꿔 가며 자연키 다수를 만든다. 나머지 필드는 고정해 이름 · 불일치 여부가 오직
        // 주소(자연키) 하나에서 갈라지도록 한다.
        Map<String, Set<Boolean>> mismatchFlagsByName = new HashMap<>();

        for (int index = 0; index < 2_000; index++) {
            PropertyNaturalKey naturalKey = naturalKeyOf("서울특별시 강남구 역삼동 주소-" + index);
            String name = LandlordNameGenerator.generate(naturalKey);
            boolean mismatch = LandlordNameGenerator.isIntentionalMismatch(naturalKey);

            mismatchFlagsByName.computeIfAbsent(name, key -> new HashSet<>()).add(mismatch);
        }

        // 두 값이 사실상 같은 근원에서 파생됐다면(고정 관계가 있다면) 이름마다 불일치 여부가 단
        // 하나로 고정된다. 씨앗이 다르고 눈사태 섞기로 그 차이가 전 비트에 흩어졌다면 표본이 충분할
        // 때 적어도 한 이름은 참 · 거짓이 섞여 나온다.
        boolean anyNameHasBothMismatchOutcomes = mismatchFlagsByName.values().stream()
                .anyMatch(outcomes -> outcomes.size() > 1);

        assertThat(anyNameHasBothMismatchOutcomes)
                .as("이름 %d종 각각의 불일치 여부 집합 — 전부 크기 1이면 두 값이 사실상 같은 근원에서 나온 것",
                        mismatchFlagsByName.size())
                .isTrue();
    }

    @Test
    @DisplayName("이름 선택과 불일치 판정의 두 파생값 사이에 고정된 상수 차이가 없다")
    void derivedValuesForTheTwoSeedsHaveNoFixedConstantDifference() throws Exception {
        // generate · isIntentionalMismatch 는 각각 모듈러(이름 풀 크기 · 100)를 거친 뒤의 값만
        // 돌려준다. "고정된 상수 차이가 없다"는 클래스 Javadoc의 주장은 모듈러 이전, derive() 가
        // 만드는 원값 수준의 성질이라 리플렉션으로 그 값을 직접 비교해야 확인된다.
        Method derive = LandlordNameGenerator.class
                .getDeclaredMethod("derive", PropertyNaturalKey.class, long.class);
        derive.setAccessible(true);
        long landlordSeed = readSeed("LANDLORD_SEED");
        long mismatchSeed = readSeed("MISMATCH_SEED");

        Set<Long> differences = new HashSet<>();
        for (int index = 0; index < 500; index++) {
            PropertyNaturalKey naturalKey = naturalKeyOf("서울특별시 강남구 역삼동 상수차이-" + index);
            long landlordDerived = (long) derive.invoke(null, naturalKey, landlordSeed);
            long mismatchDerived = (long) derive.invoke(null, naturalKey, mismatchSeed);
            differences.add(landlordDerived - mismatchDerived);
        }

        // 고정된 상수 차이라면 차이 집합의 크기가 1이다. 눈사태 섞기가 씨앗 차이를 전 비트로 흩어
        // 놓으므로 자연키가 바뀌면 차이도 함께 바뀌어야 한다.
        assertThat(differences.size())
                .as("서로 다른 자연키 500개에서 나온 두 파생값의 차이 — 전부 같으면 상수 관계가 남은 것")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("목표 불일치 비율(20%) 근방으로 갈린다 — 항상 일치 또는 항상 불일치로 쏠리지 않는다")
    void mismatchRatioIsClosetoTheConfiguredTarget() {
        int sampleSize = 5_000;
        long mismatchCount = 0;
        for (int index = 0; index < sampleSize; index++) {
            PropertyNaturalKey naturalKey = naturalKeyOf("서울특별시 마포구 합정동 표본-" + index);
            if (LandlordNameGenerator.isIntentionalMismatch(naturalKey)) {
                mismatchCount++;
            }
        }

        double ratio = (double) mismatchCount / sampleSize * 100;

        // 해시 기반이라 정확히 20%는 아니지만, RISK-04 검증 케이스가 실제로 존재한다는 것을 보이면
        // 충분하다 — 0%(항상 일치)나 100%(항상 불일치)가 아님을 넉넉한 범위로 확인한다.
        assertThat(ratio).isBetween(10.0, 30.0);
    }

    private PropertyNaturalKey naturalKeyOf(String address) {
        return new PropertyNaturalKey(address, new BigDecimal("59.90"), 3, 300_000_000L, 0L);
    }

    private long readSeed(String fieldName) throws Exception {
        Field field = LandlordNameGenerator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getLong(null);
    }
}
