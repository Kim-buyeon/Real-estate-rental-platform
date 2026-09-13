package com.duri.rentalplatform.external.registry;

import com.duri.rentalplatform.domain.property.calculator.LandlordNameGenerator;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.risk.enums.MortgageRightType;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.RegistryDataSource;
import com.duri.rentalplatform.external.registry.RegistryDocument.MortgageEntry;
import com.duri.rentalplatform.external.registry.RegistryDocument.OwnershipEntry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 등기부등본 Mock. 매물 ID 에서 등기를 만들어 <b>같은 매물은 항상 같은 등기</b>가 되게 한다.
 *
 * <p>난수나 시각을 쓰지 않는다. 같은 매물을 두 번 떼었을 때 내용이 달라지면 등기 변동 점검(RISK-08)이
 * 가짜 변동을 잡는다. 각 항목은 매물 ID 와 항목별 씨앗을 섞기 함수에 태워 고른다. 씨앗이 다르면 결과가 전
 * 비트에서 갈리므로 항목끼리 함께 움직이지 않는다 — 근저당이 있는 매물에만 압류가 붙는 식의 쏠림이 없다.
 *
 * <p><b>분포</b> — 뒤따르는 판정(깡통전세 RISK-02 · 권리 침해 RISK-03 · 명의 정합 RISK-04)의 분기가 매물
 * 수백 건 규모에서 모두 여러 번 실행되게 하는 것이 목적이다. <b>실제 서울 등기의 발생률이 아니다.</b> 그런
 * 통계를 근거로 삼은 문서가 없고, 여기 값이 현실을 대표한다고 쓰면 판정 결과 분포를 현실로 오해하게 된다.
 * 그래서 두 원칙으로만 정했다.
 * <ul>
 *   <li>깨끗한 매물이 다수다 — 권리 침해 · 경고 항목은 각 3~5% 로 낮게 둔다. 침해가 흔하면 안전 등급이
 *       거의 나오지 않아 대출 한도 화면을 확인할 매물이 모자란다.</li>
 *   <li>근저당은 흔하다 — 없음 40% · 1건 40% · 2건 20%. 금액은 시세의 20~50% 에서 골라, 보증금과 합쳤을 때
 *       판정 기준선의 양쪽에 매물이 고루 걸리게 한다. 기준선 자체는 기준 테이블이 갖고 여기서 읽지 않는다.</li>
 * </ul>
 * 표본 2,000건 기준 가장 드문 항목(3%)이 약 60건이다.
 *
 * <p><b>소유자 불일치</b>는 여기서 비율을 정하지 않는다. 임대인명을 만든 쪽이 정한 비율
 * ({@link LandlordNameGenerator#INTENTIONAL_MISMATCH_PERCENT})을 그 쪽 판정 함수로 묻는다. 두 곳이 따로
 * 정하면 검증 케이스 수를 한 곳에서 셀 수 없다.
 *
 * <p>이름 · 기관명은 실제 인물 · 회사와 무관한 조합과 가림 표기(○○)다.
 */
@Component
@ConditionalOnProperty(prefix = "external.registry", name = "mode", havingValue = "mock",
        matchIfMissing = true)
public class MockRegistryClient implements RegistryClient {

    // ---- 분포(%) — 클래스 주석의 두 원칙으로 정한 설계값 ----

    /** 소유권이전 0건(보존 등기만) · 1건. 나머지는 2건. */
    static final int NO_TRANSFER_PERCENT = 30;
    static final int ONE_TRANSFER_PERCENT = 50;

    /** 근저당 0건 · 1건. 나머지는 2건. */
    static final int NO_MORTGAGE_PERCENT = 40;
    static final int ONE_MORTGAGE_PERCENT = 40;

    /** 근저당 한 건이 말소되어 있을 확률. 말소분을 합산에서 빼는 분기를 태운다. */
    static final int DISCHARGED_MORTGAGE_PERCENT = 15;

    /** 선순위 임차인(임차권)이 있는 매물. */
    static final int PRIOR_TENANT_PERCENT = 10;

    static final int SEIZURE_PERCENT = 5;
    static final int PROVISIONAL_SEIZURE_PERCENT = 5;
    static final int AUCTION_COMMENCEMENT_PERCENT = 3;
    static final int TRUST_PERCENT = 5;
    static final int PROVISIONAL_REGISTRATION_PERCENT = 5;
    static final int TENANCY_REGISTRATION_ORDER_PERCENT = 5;

    // ---- 금액 ----

    /** 근저당 대출 원금 = 시세 × 이 중 하나(%). */
    private static final int[] LOAN_TO_PRICE_PERCENTS = {20, 30, 40, 50};

    /** 채권최고액 = 대출 원금 × 120% — 데이터베이스 설계서 3장 9절 {@code max_bond_amount} 「통상 대출액의 120%」. */
    private static final long MAX_CLAIM_PERCENT = 120;

    /** 선순위 임차보증금 = 시세 × 20%. */
    private static final long PRIOR_TENANT_DEPOSIT_PERCENT = 20;

    /** 금액을 이 단위로 내림한다. 실제 대출 원금이 백만 원 단위로 잡히는 것을 흉내 낸다. */
    private static final long AMOUNT_UNIT = 1_000_000L;

    // ---- 일자 — 시각에 무관해야 하므로 오늘이 아니라 고정 기준일에서 센다 ----

    private static final LocalDate PRESERVATION_BASE_DATE = LocalDate.of(2005, 1, 1);
    private static final int PRESERVATION_SPAN_DAYS = 3_650;
    private static final int TRANSFER_MIN_GAP_DAYS = 365;
    private static final int TRANSFER_GAP_SPAN_DAYS = 1_460;
    private static final int RIGHT_MIN_GAP_DAYS = 400;
    private static final int RIGHT_GAP_SPAN_DAYS = 700;
    private static final int SECOND_MORTGAGE_MIN_GAP_DAYS = 180;
    private static final int SECOND_MORTGAGE_GAP_SPAN_DAYS = 720;
    private static final int PRIOR_TENANT_MIN_GAP_DAYS = 30;
    private static final int PRIOR_TENANT_GAP_SPAN_DAYS = 300;

    // ---- 표제부 ----

    private static final String APARTMENT_PURPOSE = "공동주택";
    private static final String OFFICETEL_PURPOSE = "업무시설";
    private static final String BUILDING_STRUCTURE = "철근콘크리트구조";

    // ---- 등기원인 ----

    private static final String CAUSE_SALE = "매매";
    private static final String CAUSE_MORTGAGE = "설정계약";
    private static final String CAUSE_LEASE_CONTRACT = "주택임대차계약";
    private static final String CAUSE_SEIZURE = "압류";
    private static final String CAUSE_PROVISIONAL_SEIZURE = "가압류결정";
    private static final String CAUSE_VOLUNTARY_AUCTION = "임의경매개시결정";
    private static final String CAUSE_COMPULSORY_AUCTION = "강제경매개시결정";
    private static final String CAUSE_TRUST = "신탁";
    private static final String CAUSE_SALE_RESERVATION = "매매예약";
    private static final String CAUSE_TENANCY_ORDER = "임차권등기명령";

    // ---- 이름 ----

    private static final List<String> PERSON_NAMES = List.of(
            "김도현", "이서진", "박하준", "최윤서", "정민재", "강지안", "조은호", "윤채원", "장태오", "임소율",
            "한결", "오세린", "서준혁", "신다온", "권나윤");
    private static final List<String> BANKS = List.of("○○은행", "△△은행", "□□저축은행");
    private static final List<String> SEIZURE_AUTHORITIES = List.of("○○세무서", "○○구청");
    private static final String PROVISIONAL_SEIZURE_CREDITOR = "○○캐피탈 주식회사";
    private static final String TRUSTEE = "○○자산신탁 주식회사";

    // ---- 섞기 ----

    private static final int PERCENT_SCALE = 100;
    /** 항목별 씨앗을 벌려 놓는 황금비 상수. */
    private static final long SEED_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long MIX_MULTIPLIER_HIGH = 0xFF51AFD7ED558CCDL;
    private static final long MIX_MULTIPLIER_LOW = 0xC4CEB9FE1A85EC53L;
    private static final int MIX_SHIFT = 33;

    /** 항목별 씨앗. 값은 서로 다르기만 하면 된다. 순서를 바꾸면 이미 수집된 등기와 새로 수집한 등기가 달라진다. */
    private enum Draw {
        TRANSFER_COUNT, PRESERVATION_DATE, TRANSFER_GAP, PREVIOUS_OWNER_NAME, CURRENT_OWNER_NAME,
        MORTGAGE_COUNT, MORTGAGE_BANK, LOAN_RATIO, DISCHARGED, SECOND_MORTGAGE_GAP,
        PRIOR_TENANT, PRIOR_TENANT_GAP, PRIOR_TENANT_NAME,
        SEIZURE, PROVISIONAL_SEIZURE, AUCTION, TRUST, PROVISIONAL_REGISTRATION, TENANCY_ORDER,
        RIGHT_GAP, RIGHT_HOLDER
    }

    @Override
    public RegistryDocument fetch(RegistryLookup lookup) {
        long id = lookup.propertyId();

        List<OwnershipEntry> ownerships = new ArrayList<>();
        String currentOwner = currentOwnerName(lookup);
        LocalDate acquiredDate = addOwnershipChanges(id, currentOwner, ownerships);

        List<MortgageEntry> mortgages = new ArrayList<>();
        addMortgages(id, lookup.marketPrice(), currentOwner, acquiredDate, mortgages);
        addPriorTenant(id, lookup.marketPrice(), currentOwner, acquiredDate, mortgages);

        boolean hasActiveMortgage = mortgages.stream()
                .anyMatch(m -> m.rightType() == MortgageRightType.MORTGAGE && m.isActive());
        addRightEntries(id, acquiredDate, hasActiveMortgage, ownerships);

        return new RegistryDocument(
                buildingPurpose(lookup.propertyType()),
                BUILDING_STRUCTURE,
                RegistryDataSource.MOCK,
                rankOwnerships(ownerships),
                rankMortgages(mortgages));
    }

    /** 현재 소유자. 임대인명 생성 쪽이 불일치로 정한 매물만 다른 이름을 쓴다. */
    private String currentOwnerName(RegistryLookup lookup) {
        if (!LandlordNameGenerator.isIntentionalMismatch(lookup.naturalKey())) {
            return lookup.landlordName();
        }
        return pickOtherThan(lookup.landlordName(), pick(lookup.propertyId(), Draw.CURRENT_OWNER_NAME, 0,
                PERSON_NAMES.size()));
    }

    /** 보존 등기 1건 + 이전 등기 0~2건. 마지막 행이 현재 소유자다. 현재 소유자의 취득일을 돌려준다. */
    private LocalDate addOwnershipChanges(long id, String currentOwner, List<OwnershipEntry> ownerships) {
        int transferDraw = percent(id, Draw.TRANSFER_COUNT, 0);
        int transferCount = transferDraw < NO_TRANSFER_PERCENT ? 0
                : transferDraw < NO_TRANSFER_PERCENT + ONE_TRANSFER_PERCENT ? 1 : 2;

        LocalDate date = PRESERVATION_BASE_DATE.plusDays(pick(id, Draw.PRESERVATION_DATE, 0, PRESERVATION_SPAN_DAYS));
        for (int index = 0; index <= transferCount; index++) {
            boolean isCurrent = index == transferCount;
            if (index > 0) {
                date = date.plusDays(TRANSFER_MIN_GAP_DAYS + pick(id, Draw.TRANSFER_GAP, index, TRANSFER_GAP_SPAN_DAYS));
            }
            String holder = isCurrent ? currentOwner
                    : pickOtherThan(currentOwner, pick(id, Draw.PREVIOUS_OWNER_NAME, index, PERSON_NAMES.size()));
            OwnershipRightType type = index == 0
                    ? OwnershipRightType.OWNERSHIP_PRESERVATION : OwnershipRightType.OWNERSHIP_TRANSFER;
            // 보존 등기는 원인을 적지 않는다 — 원시취득이라 매매 같은 원인 행위가 없다.
            String cause = index == 0 ? null : CAUSE_SALE;
            ownerships.add(new OwnershipEntry(0, type, holder, date, cause, isCurrent));
        }
        return date;
    }

    /** 근저당 0~2건. 첫 건은 취득일(잔금일 대출), 둘째 건은 그 뒤다. */
    private void addMortgages(long id, long marketPrice, String debtor, LocalDate acquiredDate,
            List<MortgageEntry> mortgages) {
        int countDraw = percent(id, Draw.MORTGAGE_COUNT, 0);
        int count = countDraw < NO_MORTGAGE_PERCENT ? 0
                : countDraw < NO_MORTGAGE_PERCENT + ONE_MORTGAGE_PERCENT ? 1 : 2;

        LocalDate date = acquiredDate;
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                date = date.plusDays(SECOND_MORTGAGE_MIN_GAP_DAYS
                        + pick(id, Draw.SECOND_MORTGAGE_GAP, index, SECOND_MORTGAGE_GAP_SPAN_DAYS));
            }
            int ratio = LOAN_TO_PRICE_PERCENTS[pick(id, Draw.LOAN_RATIO, index, LOAN_TO_PRICE_PERCENTS.length)];
            long loan = portion(marketPrice, ratio);
            long maxClaim = Math.multiplyExact(loan, MAX_CLAIM_PERCENT) / PERCENT_SCALE;
            boolean isActive = percent(id, Draw.DISCHARGED, index) >= DISCHARGED_MORTGAGE_PERCENT;
            mortgages.add(new MortgageEntry(0, MortgageRightType.MORTGAGE,
                    BANKS.get(pick(id, Draw.MORTGAGE_BANK, index, BANKS.size())), debtor, date, CAUSE_MORTGAGE,
                    loan, maxClaim, 0L, isActive));
        }
    }

    /** 선순위 임차인. 채권최고액은 없고 보증금이 선순위 임차보증금으로 잡힌다. */
    private void addPriorTenant(long id, long marketPrice, String owner, LocalDate acquiredDate,
            List<MortgageEntry> mortgages) {
        if (percent(id, Draw.PRIOR_TENANT, 0) >= PRIOR_TENANT_PERCENT) {
            return;
        }
        LocalDate date = acquiredDate.plusDays(
                PRIOR_TENANT_MIN_GAP_DAYS + pick(id, Draw.PRIOR_TENANT_GAP, 0, PRIOR_TENANT_GAP_SPAN_DAYS));
        String tenant = pickOtherThan(owner, pick(id, Draw.PRIOR_TENANT_NAME, 0, PERSON_NAMES.size()));
        mortgages.add(new MortgageEntry(0, MortgageRightType.TENANCY, tenant, owner, date, CAUSE_LEASE_CONTRACT,
                0L, 0L, portion(marketPrice, PRIOR_TENANT_DEPOSIT_PERCENT), true));
    }

    /** 권리 침해 · 경고 항목. 모두 현재 소유자 취득 뒤에 접수되고 말소되지 않은 상태다. */
    private void addRightEntries(long id, LocalDate acquiredDate, boolean hasActiveMortgage,
            List<OwnershipEntry> ownerships) {
        addRightIfDrawn(id, Draw.SEIZURE, SEIZURE_PERCENT, OwnershipRightType.SEIZURE,
                SEIZURE_AUTHORITIES.get(pick(id, Draw.RIGHT_HOLDER, Draw.SEIZURE.ordinal(), SEIZURE_AUTHORITIES.size())),
                CAUSE_SEIZURE, acquiredDate, ownerships);
        addRightIfDrawn(id, Draw.PROVISIONAL_SEIZURE, PROVISIONAL_SEIZURE_PERCENT,
                OwnershipRightType.PROVISIONAL_SEIZURE, PROVISIONAL_SEIZURE_CREDITOR, CAUSE_PROVISIONAL_SEIZURE,
                acquiredDate, ownerships);
        // 근저당이 살아 있으면 근저당권자가 임의경매를, 없으면 일반 채권자가 강제경매를 신청한 것으로 둔다.
        addRightIfDrawn(id, Draw.AUCTION, AUCTION_COMMENCEMENT_PERCENT, OwnershipRightType.AUCTION_COMMENCEMENT,
                hasActiveMortgage ? BANKS.get(pick(id, Draw.MORTGAGE_BANK, 0, BANKS.size()))
                        : PROVISIONAL_SEIZURE_CREDITOR,
                hasActiveMortgage ? CAUSE_VOLUNTARY_AUCTION : CAUSE_COMPULSORY_AUCTION, acquiredDate, ownerships);
        addRightIfDrawn(id, Draw.TRUST, TRUST_PERCENT, OwnershipRightType.TRUST, TRUSTEE, CAUSE_TRUST,
                acquiredDate, ownerships);
        addRightIfDrawn(id, Draw.PROVISIONAL_REGISTRATION, PROVISIONAL_REGISTRATION_PERCENT,
                OwnershipRightType.PROVISIONAL_REGISTRATION,
                PERSON_NAMES.get(pick(id, Draw.RIGHT_HOLDER, Draw.PROVISIONAL_REGISTRATION.ordinal(),
                        PERSON_NAMES.size())),
                CAUSE_SALE_RESERVATION, acquiredDate, ownerships);
        addRightIfDrawn(id, Draw.TENANCY_ORDER, TENANCY_REGISTRATION_ORDER_PERCENT,
                OwnershipRightType.TENANCY_REGISTRATION_ORDER,
                PERSON_NAMES.get(pick(id, Draw.RIGHT_HOLDER, Draw.TENANCY_ORDER.ordinal(), PERSON_NAMES.size())),
                CAUSE_TENANCY_ORDER, acquiredDate, ownerships);
    }

    private void addRightIfDrawn(long id, Draw draw, int percentThreshold, OwnershipRightType type, String holder,
            String cause, LocalDate acquiredDate, List<OwnershipEntry> ownerships) {
        if (percent(id, draw, 0) >= percentThreshold) {
            return;
        }
        LocalDate date = acquiredDate.plusDays(
                RIGHT_MIN_GAP_DAYS + pick(id, Draw.RIGHT_GAP, draw.ordinal(), RIGHT_GAP_SPAN_DAYS));
        ownerships.add(new OwnershipEntry(0, type, holder, date, cause, true));
    }

    private String buildingPurpose(PropertyType propertyType) {
        return switch (propertyType) {
            case APARTMENT -> APARTMENT_PURPOSE;
            case OFFICETEL -> OFFICETEL_PURPOSE;
        };
    }

    /** 접수일 순으로 세우고 순위번호를 1부터 매긴다. 같은 날이면 넣은 순서를 지킨다(정렬이 안정적이다). */
    private List<OwnershipEntry> rankOwnerships(List<OwnershipEntry> entries) {
        List<OwnershipEntry> sorted = entries.stream()
                .sorted(Comparator.comparing(OwnershipEntry::receivedDate))
                .toList();
        return IntStream.range(0, sorted.size())
                .mapToObj(index -> {
                    OwnershipEntry e = sorted.get(index);
                    return new OwnershipEntry(index + 1, e.rightType(), e.holderName(), e.receivedDate(), e.cause(),
                            e.isActive());
                })
                .toList();
    }

    private List<MortgageEntry> rankMortgages(List<MortgageEntry> entries) {
        List<MortgageEntry> sorted = entries.stream()
                .sorted(Comparator.comparing(MortgageEntry::receivedDate))
                .toList();
        return IntStream.range(0, sorted.size())
                .mapToObj(index -> {
                    MortgageEntry e = sorted.get(index);
                    return new MortgageEntry(index + 1, e.rightType(), e.creditor(), e.debtorName(),
                            e.receivedDate(), e.cause(), e.loanAmount(), e.maxClaimAmount(),
                            e.priorTenantDeposit(), e.isActive());
                })
                .toList();
    }

    /**
     * 시세의 일정 비율을 금액 단위로 내림한다. 정수 곱셈 뒤 나눗셈이라 부동소수점을 거치지 않는다.
     */
    private static long portion(long marketPrice, long percentOfPrice) {
        long amount = Math.multiplyExact(marketPrice, percentOfPrice) / PERCENT_SCALE;
        return amount / AMOUNT_UNIT * AMOUNT_UNIT;
    }

    /** {@code excluded} 와 다른 이름. 고른 이름이 같으면 다음 이름을 쓴다. */
    private static String pickOtherThan(String excluded, int index) {
        String name = PERSON_NAMES.get(index);
        return name.equals(excluded) ? PERSON_NAMES.get((index + 1) % PERSON_NAMES.size()) : name;
    }

    private static int percent(long id, Draw draw, int index) {
        return pick(id, draw, index, PERCENT_SCALE);
    }

    /** 매물 · 항목 · 순번이 같으면 같은 값. 0 이상 {@code bound} 미만. */
    private static int pick(long id, Draw draw, int index, int bound) {
        long seed = (draw.ordinal() + 1L) * SEED_GAMMA + index;
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
