package com.duri.rentalplatform.external.buildingledger;

import com.duri.rentalplatform.domain.property.calculator.LandlordNameGenerator;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 건축물대장 Mock. 매물에서 대장을 만들어 <b>같은 매물은 항상 같은 대장</b>이 되게 한다.
 *
 * <p>난수나 시각을 쓰지 않는다. 각 항목은 매물 ID 와 항목별 씨앗을 섞기 함수에 태워 고른다 — 등기 Mock 과 같은
 * 방식이다. 씨앗이 다르면 결과가 전 비트에서 갈리므로 항목끼리 함께 움직이지 않는다.
 *
 * <p><b>주용도</b> — 매물 유형에서 정한다. 아파트는 공동주택, 오피스텔은 업무시설이다(건축법 시행령 별표 1 제2호 ·
 * 제14호). 매물 유형은 지금 이 둘뿐이다. 연립 · 다세대(공동주택) · 단독 · 다가구(단독주택)는 그 유형이 적재되는 변경에서
 * 분기가 늘어난다 — {@code switch} 가 열거형 전체를 다루므로 유형을 더하면 여기서 컴파일이 멈춘다.
 *
 * <p><b>분포</b> — 등기 Mock 과 같은 원칙이다. 뒤따르는 판정의 분기가 매물 수백 건 규모에서 여러 번 실행되게 하는
 * 것이 목적이고 <b>실제 서울 건축물의 발생률이 아니다.</b> 그런 통계를 근거로 삼은 문서가 없다.
 * <ul>
 *   <li>위반건축물 {@value #VIOLATION_PERCENT}% — 등기 Mock 의 권리 침해 항목(3~5%)과 같은 높이로 낮게 둔다.
 *       위반건축물은 보증보험 가입을 막는 항목이라, 흔하면 안전 등급 매물이 모자란다. 표본 2,000건이면 약 100건이다.</li>
 *   <li>대장 소유자 불일치 — 여기서 비율을 정하지 않는다. 임대인명을 만든 쪽이 정한 비율
 *       ({@link LandlordNameGenerator#INTENTIONAL_MISMATCH_PERCENT})을 그 쪽 판정 함수로 묻는다. 등기 Mock 도 같은
 *       함수를 물으므로 대장 · 등기 모두 같은 매물에서 임대인과 어긋난다. 어긋난 이름끼리는 같다고 보장하지 않는다.</li>
 * </ul>
 *
 * <p><b>면적 · 일자</b>
 * <ul>
 *   <li>전용면적은 매물의 전용면적 그대로다. 대장과 매물이 다르면 이 화면에서 이유 없는 불일치가 보인다.</li>
 *   <li>연면적은 전용면적 × {@value #MIN_FLOOR_AREA_MULTIPLIER}~{@value #MAX_FLOOR_AREA_MULTIPLIER}배다. 한 동에 같은
 *       평형 호실이 그만큼 있는 규모를 흉내 낸다. 연면적이 전용면적보다 작아지지 않는다.</li>
 *   <li>건축면적은 연면적의 {@value #MIN_BUILDING_AREA_PERCENT}~{@value #MAX_BUILDING_AREA_PERCENT}% 다. 여러 층으로
 *       올린 건물의 한 층 바닥에 해당한다. 건축면적이 연면적을 넘지 않는다.</li>
 *   <li>사용승인일은 {@code 1985-01-01} 부터 20년 안이다. 등기 Mock 의 소유권보존일이 2005년 이후라, 사용승인 뒤에
 *       보존 등기가 오는 순서가 어긋나지 않는다.</li>
 * </ul>
 *
 * <p>이름은 실제 인물과 무관한 조합이다.
 */
@Component
@ConditionalOnProperty(prefix = "external.building-ledger", name = "mode", havingValue = "mock",
        matchIfMissing = true)
public class MockBuildingLedgerClient implements BuildingLedgerClient {

    /** 위반건축물 비율(%) — 클래스 주석의 원칙으로 정한 설계값. */
    static final int VIOLATION_PERCENT = 5;

    static final int MIN_FLOOR_AREA_MULTIPLIER = 30;
    static final int MAX_FLOOR_AREA_MULTIPLIER = 150;
    static final int MIN_BUILDING_AREA_PERCENT = 10;
    static final int MAX_BUILDING_AREA_PERCENT = 40;

    static final LocalDate APPROVAL_BASE_DATE = LocalDate.of(1985, 1, 1);
    static final int APPROVAL_SPAN_DAYS = 7_300;

    /** 공동주택 · 업무시설 — 건축법 시행령 별표 1 의 용도 이름. */
    static final String APARTMENT_PURPOSE = "공동주택";
    static final String OFFICETEL_PURPOSE = "업무시설";
    private static final String BUILDING_STRUCTURE = "철근콘크리트구조";

    private static final int AREA_SCALE = 2;

    private static final List<String> PERSON_NAMES = List.of(
            "김도현", "이서진", "박하준", "최윤서", "정민재", "강지안", "조은호", "윤채원", "장태오", "임소율",
            "한결", "오세린", "서준혁", "신다온", "권나윤");

    // ---- 섞기 ----

    private static final int PERCENT_SCALE = 100;
    private static final long SEED_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long MIX_MULTIPLIER_HIGH = 0xFF51AFD7ED558CCDL;
    private static final long MIX_MULTIPLIER_LOW = 0xC4CEB9FE1A85EC53L;
    private static final int MIX_SHIFT = 33;

    /** 항목별 씨앗. 순서를 바꾸면 이미 수집된 대장과 새로 수집한 대장이 달라진다. */
    private enum Draw {
        VIOLATION, FLOOR_AREA, BUILDING_AREA, APPROVAL_DATE, OWNER_NAME
    }

    @Override
    public BuildingLedgerDocument fetch(BuildingLedgerLookup lookup) {
        long id = lookup.propertyId();
        BigDecimal exclusiveArea = lookup.naturalKey().areaSqm();

        int floorMultiplier = MIN_FLOOR_AREA_MULTIPLIER
                + pick(id, Draw.FLOOR_AREA, MAX_FLOOR_AREA_MULTIPLIER - MIN_FLOOR_AREA_MULTIPLIER);
        BigDecimal totalFloorArea = exclusiveArea.multiply(BigDecimal.valueOf(floorMultiplier))
                .setScale(AREA_SCALE, RoundingMode.HALF_UP);

        int buildingPercent = MIN_BUILDING_AREA_PERCENT
                + pick(id, Draw.BUILDING_AREA, MAX_BUILDING_AREA_PERCENT - MIN_BUILDING_AREA_PERCENT);
        BigDecimal buildingArea = totalFloorArea.multiply(BigDecimal.valueOf(buildingPercent))
                .divide(BigDecimal.valueOf(PERCENT_SCALE), AREA_SCALE, RoundingMode.HALF_UP);

        return new BuildingLedgerDocument(
                lookup.naturalKey().address(),
                ownerName(lookup),
                mainPurpose(lookup.propertyType()),
                BUILDING_STRUCTURE,
                buildingArea,
                totalFloorArea,
                exclusiveArea,
                APPROVAL_BASE_DATE.plusDays(pick(id, Draw.APPROVAL_DATE, APPROVAL_SPAN_DAYS)),
                pick(id, Draw.VIOLATION, PERCENT_SCALE) < VIOLATION_PERCENT,
                LedgerDataSource.MOCK);
    }

    /** 대장 소유자. 임대인명 생성 쪽이 불일치로 정한 매물만 다른 이름을 쓴다. */
    private String ownerName(BuildingLedgerLookup lookup) {
        if (!LandlordNameGenerator.isIntentionalMismatch(lookup.naturalKey())) {
            return lookup.landlordName();
        }
        int index = pick(lookup.propertyId(), Draw.OWNER_NAME, PERSON_NAMES.size());
        String name = PERSON_NAMES.get(index);
        // 고른 이름이 임대인명과 같으면 다음 이름을 쓴다. 불일치로 정한 매물이 우연히 일치하면 안 된다.
        return name.equals(lookup.landlordName()) ? PERSON_NAMES.get((index + 1) % PERSON_NAMES.size()) : name;
    }

    private static String mainPurpose(PropertyType propertyType) {
        return switch (propertyType) {
            case APARTMENT -> APARTMENT_PURPOSE;
            case OFFICETEL -> OFFICETEL_PURPOSE;
        };
    }

    /** 매물 · 항목이 같으면 같은 값. 0 이상 {@code bound} 미만. */
    private static int pick(long id, Draw draw, int bound) {
        long seed = (draw.ordinal() + 1L) * SEED_GAMMA;
        return (int) Math.floorMod(mix(mix(id) + seed), (long) bound);
    }

    /** murmur3 64비트 마무리 단계. 입력의 한 비트 차이가 출력 전 비트로 번진다. */
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
