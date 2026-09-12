package com.duri.rentalplatform.domain.property.calculator;

import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import java.util.List;

/**
 * 임대인명 생성. {@code property.landlord_name} 은 NOT NULL 인데 실거래가 API 는 임대인명을 주지 않는다
 * (개인정보보호로 동 · 호도 비공개다). 그래서 생성값을 넣는다.
 *
 * <p><b>생성 방식</b> — 매물의 자연키 문자열에 이 클래스 전용 소금({@link #LANDLORD_SALT})을 붙여
 * 해시하고, 그 값으로 이름 풀에서 하나를 고른다. 자연키가 같으면 항상 같은 이름이 나오므로 적재를
 * 다시 돌려도 임대인이 바뀌지 않는다. 난수나 시각을 쓰지 않는 이유가 이것이다.
 *
 * <p><b>왜 등기 소유자와 독립적인 경로여야 하는가</b> — RISK-04 는 임대인명과 등기상 소유자명이
 * 일치하는지를 판정한다. 두 값을 같은 출처에서 파생하면 판정이 <b>언제나 일치</b>로 나와 검증이
 * 죽는다. 불일치 사례가 하나도 없으면 그 판정 분기는 한 번도 실행되지 않는다.
 *
 * <p><b>불일치 비율</b> — 목표는 {@link #INTENTIONAL_MISMATCH_PERCENT}% 다. 등기 Mock 이 소유자명을
 * 만들 때 {@link #isIntentionalMismatch(PropertyNaturalKey)} 를 물어, 참이면 임대인명과 다른 이름을
 * 고르고 거짓이면 임대인명을 그대로 쓰면 된다. 비율이 코드 한 곳에 적혀 있어야 「검증 케이스가
 * 몇 건인지」를 셀 수 있다. 등기 Mock 은 이 슬라이스 범위 밖이라 그 쪽 구현은 아직 없다.
 *
 * <p>여기서 만드는 이름은 실제 인물과 무관한 조합이다. 운영 데이터를 쓰지 않는다.
 */
public final class LandlordNameGenerator {

    private LandlordNameGenerator() {
    }

    /** 임대인명 파생 전용 소금. 등기 Mock 은 이 값을 쓰지 않는다 — 두 경로가 섞이면 안 된다. */
    private static final String LANDLORD_SALT = "LANDLORD";

    /** 불일치 판정 전용 소금. 이름 선택과 다른 비트를 쓰기 위해 분리한다. */
    private static final String MISMATCH_SALT = "OWNER_MISMATCH";

    /** 등기 소유자와 일부러 어긋나게 둘 비율(%). */
    public static final int INTENTIONAL_MISMATCH_PERCENT = 20;

    private static final List<String> FAMILY_NAMES =
            List.of("김", "이", "박", "최", "정", "강", "조", "윤", "장", "임");

    private static final List<String> GIVEN_NAMES =
            List.of("민준", "서연", "도윤", "지우", "예준", "하은", "시우", "지민", "주원", "수아",
                    "건우", "유진", "현우", "다인", "준서");

    /**
     * 자연키에서 임대인명을 만든다. 같은 매물이면 항상 같은 이름이다.
     */
    public static String generate(PropertyNaturalKey naturalKey) {
        int hash = hash(naturalKey, LANDLORD_SALT);
        String familyName = FAMILY_NAMES.get(Math.floorMod(hash, FAMILY_NAMES.size()));
        String givenName = GIVEN_NAMES.get(Math.floorMod(hash >>> 8, GIVEN_NAMES.size()));
        return familyName + givenName;
    }

    /**
     * 이 매물을 등기상 소유자와 불일치로 둘 것인지. 등기 Mock 이 물어보는 자리다.
     */
    public static boolean isIntentionalMismatch(PropertyNaturalKey naturalKey) {
        return Math.floorMod(hash(naturalKey, MISMATCH_SALT), 100) < INTENTIONAL_MISMATCH_PERCENT;
    }

    /** 문자열 hashCode 는 자바 명세가 고정한 값이라 실행 · JVM 이 달라도 같다. */
    private static int hash(PropertyNaturalKey naturalKey, String salt) {
        return "%s|%s|%s|%s|%s|%s".formatted(
                salt,
                naturalKey.address(),
                naturalKey.areaSqm(),
                naturalKey.floor(),
                naturalKey.deposit(),
                naturalKey.monthlyRent()).hashCode();
    }
}
