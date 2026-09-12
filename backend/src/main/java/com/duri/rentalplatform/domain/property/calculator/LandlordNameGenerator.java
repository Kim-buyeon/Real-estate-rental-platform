package com.duri.rentalplatform.domain.property.calculator;

import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import java.util.List;

/**
 * 임대인명 생성. {@code property.landlord_name} 은 NOT NULL 인데 실거래가 API 는 임대인명을 주지 않는다
 * (개인정보보호로 동 · 호도 비공개다). 그래서 생성값을 넣는다.
 *
 * <p><b>생성 방식</b> — 매물의 자연키 문자열을 섞기 함수에 태워 64비트 값을 얻고, 그 값으로 이름
 * 풀에서 하나를 고른다. 자연키가 같으면 항상 같은 이름이 나오므로 적재를 다시 돌려도 임대인이 바뀌지
 * 않는다. 난수나 시각을 쓰지 않는 이유가 이것이다.
 *
 * <p><b>왜 등기 소유자와 독립적인 경로여야 하는가</b> — RISK-04 는 임대인명과 등기상 소유자명이
 * 일치하는지를 판정한다. 두 값이 서로를 예측할 수 있으면 판정이 <b>언제나 일치</b>(또는 언제나
 * 불일치)로 나와 검증이 죽는다. 불일치 사례가 하나도 없으면 그 판정 분기는 한 번도 실행되지 않는다.
 *
 * <p><b>독립성을 무엇이 보장하는가</b> — 씨앗을 다르게 준 것만으로는 보장되지 않는다.
 * {@code String.hashCode} 는 접두사에 대해 선형이라
 * {@code hash(씨앗 + 자연키) = hash(씨앗) × 31^자연키길이 + hash(자연키)} 이고, 자연키 길이가 같으면
 * <b>두 해시의 차이가 상수</b>다. 즉 한쪽 값에서 다른 쪽 값이 곧바로 나온다. 그래서 파생 경로를
 * 섞기 함수로 바꿨다. 자연키를 FNV-1a 로 접은 뒤 씨앗을 더하고 {@link #mix(long)} 의
 * 자리이동 · 곱셈 눈사태를 태운다. 이 마지막 단계는 입력의 <b>상수 차이를 전 비트로 퍼뜨리므로</b>,
 * 두 결과를 잇는 고정된 관계가 남지 않는다 — 모듈러를 어떻게 잡든 두 값이 함께 움직이지 않는다.
 * 등기 Mock 이 어떤 나눗수를 쓰더라도 안전해야 하므로 파생 단계에서 끊는다.
 *
 * <p><b>불일치 비율</b> — 목표는 {@link #INTENTIONAL_MISMATCH_PERCENT}% 다. 등기 Mock 이 소유자명을
 * 만들 때 {@link #isIntentionalMismatch(PropertyNaturalKey)} 를 물어, 참이면 임대인명과 다른 이름을
 * 고르고 거짓이면 임대인명을 그대로 쓰면 된다. 비율이 코드 한 곳에 적혀 있어야 「검증 케이스가
 * 몇 건인지」를 셀 수 있다. 등기 Mock 은 이 슬라이스 범위 밖이라 그 쪽 구현은 아직 없다.
 *
 * <p>여기서 만드는 이름은 실제 인물과 무관한 조합이다. 운영 데이터를 쓰지 않는다.
 */
public final class LandlordNameGenerator {

    /** 등기 소유자와 일부러 어긋나게 둘 비율(%). */
    public static final int INTENTIONAL_MISMATCH_PERCENT = 20;

    /** 임대인명 파생 씨앗. 값 자체에 뜻은 없고 아래 씨앗과 다르기만 하면 된다. */
    private static final long LANDLORD_SEED = 0x9E3779B97F4A7C15L;

    /** 불일치 판정 파생 씨앗. 이름 선택과 다른 경로를 타게 한다. */
    private static final long MISMATCH_SEED = 0xBF58476D1CE4E5B9L;

    /** FNV-1a 64비트 초기값과 소수. 자연키 문자열을 한 개의 64비트 값으로 접는다. */
    private static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    /** 눈사태 단계(murmur3 finalizer)의 곱수와 자리이동 폭. */
    private static final long MIX_MULTIPLIER_HIGH = 0xFF51AFD7ED558CCDL;
    private static final long MIX_MULTIPLIER_LOW = 0xC4CEB9FE1A85EC53L;
    private static final int MIX_SHIFT = 33;

    /** 백분율 나눗수. */
    private static final int PERCENT_SCALE = 100;

    private static final List<String> FAMILY_NAMES =
            List.of("김", "이", "박", "최", "정", "강", "조", "윤", "장", "임");

    private static final List<String> GIVEN_NAMES =
            List.of("민준", "서연", "도윤", "지우", "예준", "하은", "시우", "지민", "주원", "수아",
                    "건우", "유진", "현우", "다인", "준서");

    /**
     * 자연키에서 임대인명을 만든다. 같은 매물이면 항상 같은 이름이다.
     */
    public static String generate(PropertyNaturalKey naturalKey) {
        long hash = derive(naturalKey, LANDLORD_SEED);
        String familyName = FAMILY_NAMES.get(Math.floorMod(hash, FAMILY_NAMES.size()));
        // 성과 이름을 한 값의 다른 구간에서 꺼내지 않는다. 한 번 더 섞어 두 선택을 갈라 놓는다.
        String givenName = GIVEN_NAMES.get(Math.floorMod(mix(hash), GIVEN_NAMES.size()));
        return familyName + givenName;
    }

    /**
     * 이 매물을 등기상 소유자와 불일치로 둘 것인지. 등기 Mock 이 물어보는 자리다.
     */
    public static boolean isIntentionalMismatch(PropertyNaturalKey naturalKey) {
        return Math.floorMod(derive(naturalKey, MISMATCH_SEED), PERCENT_SCALE)
                < INTENTIONAL_MISMATCH_PERCENT;
    }

    private LandlordNameGenerator() {
    }

    /**
     * 자연키와 씨앗에서 파생값을 만든다. 씨앗이 다르면 결과가 전 비트에서 갈린다.
     *
     * <p>자연키를 접는 단계와 씨앗을 섞는 단계를 나눈 이유는 클래스 Javadoc 에 적었다. 문자열을
     * 접기만 하고 끝내면 씨앗 차이가 결과에 그대로 남는다.
     */
    private static long derive(PropertyNaturalKey naturalKey, long seed) {
        return mix(fold(naturalKeyText(naturalKey)) + seed);
    }

    /** 자연키의 문자열 표현. 필드 구성이 바뀌면 이름도 바뀌지만, 같은 적재 안에서는 고정이다. */
    private static String naturalKeyText(PropertyNaturalKey naturalKey) {
        return "%s|%s|%s|%s|%s".formatted(
                naturalKey.address(),
                naturalKey.areaSqm(),
                naturalKey.floor(),
                naturalKey.deposit(),
                naturalKey.monthlyRent());
    }

    /** FNV-1a. 자바 명세가 고정한 {@code char} 값만 쓰므로 실행 · JVM 이 달라도 결과가 같다. */
    private static long fold(String text) {
        long hash = FNV_OFFSET_BASIS;
        for (int index = 0; index < text.length(); index++) {
            hash ^= text.charAt(index);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /**
     * 눈사태 단계. 입력의 한 비트 차이가 출력 전 비트로 번진다. 씨앗만큼의 <b>상수 차이</b>도 여기서
     * 흩어지므로 두 파생값 사이에 고정된 관계가 남지 않는다.
     */
    private static long mix(long value) {
        long mixed = value;
        mixed ^= mixed >>> MIX_SHIFT;
        mixed *= MIX_MULTIPLIER_HIGH;
        mixed ^= mixed >>> MIX_SHIFT;
        mixed *= MIX_MULTIPLIER_LOW;
        mixed ^= mixed >>> MIX_SHIFT;
        return mixed;
    }
}
