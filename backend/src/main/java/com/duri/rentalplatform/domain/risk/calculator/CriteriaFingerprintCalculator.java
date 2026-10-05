package com.duri.rentalplatform.domain.risk.calculator;

import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeCriteriaSnapshot;
import com.duri.rentalplatform.domain.risk.vo.PremiumRateBand;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 위험도 판정 기준의 지문 — 저장된 판정 근거가 지금 기준으로도 맞는지 가르는 값(V21 {@code criteria_fingerprint}).
 *
 * <p>판정이 읽는 기준값을 정해진 순서의 정규 문자열로 펴고 SHA-256 16진(64자)으로 줄인다. 기준값이 하나라도 다르면 지문이 다르다.
 * 같은 값이면 슬롯 · 재기동과 무관하게 같은 지문이다 — 그래서 HashMap 순회 순서 같은 것에 기대지 않고, 소수는 자릿수를 떼고 적는다
 * ({@code 90.0} 과 {@code 90.00} 은 같은 값이다).
 *
 * <p><b>담는 것</b>
 * <ul>
 *   <li>기관별 판정 기준 — 판정기 입력({@link GuaranteeCriteriaSnapshot})의 모든 값. 넘긴 순서(HUG → HF → SGI) · 요율 구간 순서
 *       그대로다. 순서는 판정 결과(응답의 기관 순서 · 겹치는 요율 구간의 선택)를 바꾸므로 지문에도 순서대로 넣는다</li>
 *   <li>기관별 보증 기준 ID — 분석 행의 {@code eligible_guarantee_id} 가 된다</li>
 *   <li>위험 등급 기준 둘 — 깡통전세 선 · SAFE/CAUTION 경계</li>
 *   <li>건축물대장 연동 모드 — real 이면 Mock 대장을 「대장 없음」으로 보므로 같은 매물의 판정이 달라진다</li>
 *   <li>형식 판({@link #FORMAT_VERSION})</li>
 * </ul>
 *
 * <p><b>담지 않는 것</b> — 대출 규제 · 대출 상품. 위험도 판정의 입력이 아니다. 넣으면 월 1회 금리 갱신마다 모든 매물의 저장된
 * 판정이 쓸모없어져 한 번씩 다시 판정된다.
 */
public final class CriteriaFingerprintCalculator {

    /**
     * 형식 판. 기준값이 그대로여도 저장된 근거를 모두 버려야 할 때 올린다 — 근거 JSON 의 모양({@code RiskResponse.Judgement})을
     * 바꿨을 때, 또는 판정 계산기의 규칙을 바꿔 같은 입력의 결과가 달라질 때. 올리면 배포 뒤 각 매물의 첫 조회가 다시 판정한다.
     */
    public static final int FORMAT_VERSION = 1;

    private static final String NULL_VALUE = "-";

    private CriteriaFingerprintCalculator() {
    }

    /**
     * @param guaranteeCriteria   판정기에 넘기는 기관별 기준. 넘기는 순서 그대로
     * @param guaranteeIds        기관별 보증 기준 ID
     * @param negativeEquityRatio 깡통전세 선(%)
     * @param cautionLeaseRatio   SAFE/CAUTION 경계(%)
     * @param buildingLedgerMode  {@code external.building-ledger.mode}
     * @return SHA-256 16진 소문자 64자
     */
    public static String calculate(
            List<GuaranteeCriteriaSnapshot> guaranteeCriteria,
            Map<GuaranteeProvider, Long> guaranteeIds,
            BigDecimal negativeEquityRatio,
            BigDecimal cautionLeaseRatio,
            String buildingLedgerMode) {
        return sha256Hex(canonical(guaranteeCriteria, guaranteeIds, negativeEquityRatio, cautionLeaseRatio,
                buildingLedgerMode));
    }

    /** 지문을 내기 전의 정규 문자열. 무엇이 지문에 들어갔는지 테스트가 직접 볼 수 있게 열어 둔다. */
    static String canonical(
            List<GuaranteeCriteriaSnapshot> guaranteeCriteria,
            Map<GuaranteeProvider, Long> guaranteeIds,
            BigDecimal negativeEquityRatio,
            BigDecimal cautionLeaseRatio,
            String buildingLedgerMode) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("format=" + FORMAT_VERSION);
        joiner.add("buildingLedgerMode=" + text(buildingLedgerMode));
        joiner.add("risk=" + decimal(negativeEquityRatio) + "," + decimal(cautionLeaseRatio));
        for (GuaranteeCriteriaSnapshot criteria : guaranteeCriteria) {
            joiner.add(guarantee(criteria, guaranteeIds.get(criteria.provider())));
            for (PremiumRateBand band : criteria.premiumRates()) {
                joiner.add(band(criteria.provider(), band));
            }
        }
        return joiner.toString();
    }

    private static String guarantee(GuaranteeCriteriaSnapshot criteria, Long guaranteeId) {
        return new StringJoiner(",", "guarantee=", "")
                .add(criteria.provider().name())
                .add(text(guaranteeId))
                .add(decimal(criteria.collateralRatio()))
                .add(decimal(criteria.seniorDebtRatioLimit()))
                .add(Long.toString(criteria.maxDeposit()))
                .add(Boolean.toString(criteria.apartmentUnlimited()))
                .add(Boolean.toString(criteria.violationDisqualify()))
                .add(Boolean.toString(criteria.rightViolationDisqualify()))
                .add(Boolean.toString(criteria.loanLinkRequired()))
                // 상품명은 쉼표를 담을 수 있다 — 길이를 앞에 붙여 다음 칸과 섞이지 않게 한다.
                .add(criteria.productName() == null
                        ? NULL_VALUE
                        : criteria.productName().length() + ":" + criteria.productName())
                .toString();
    }

    private static String band(GuaranteeProvider provider, PremiumRateBand band) {
        return new StringJoiner(",", "rate=", "")
                .add(provider.name())
                .add(band.houseType().name())
                .add(Long.toString(band.depositMin()))
                .add(text(band.depositMax()))
                .add(decimal(band.debtRatioMin()))
                .add(decimal(band.debtRatioMax()))
                .add(decimal(band.premiumRate()))
                .toString();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? NULL_VALUE : value.stripTrailingZeros().toPlainString();
    }

    private static String text(Object value) {
        return value == null ? NULL_VALUE : value.toString();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 모든 Java 구현이 SHA-256 을 갖춰야 한다(MessageDigest 명세). 여기 오면 런타임이 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
