package com.duri.rentalplatform.domain.risk.calculator;

import com.duri.rentalplatform.external.registry.RegistryDocument.MortgageEntry;
import com.duri.rentalplatform.external.registry.RegistryDocument.OwnershipEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 등기 갑구 · 을구의 변동 전 · 후 요약 문구를 만든다(NOTI-02). 관심 매물 알림의 변동 전 · 후 값(데이터베이스 설계서 29절,
 * 100자)이 된다.
 *
 * <p><b>형식</b> — {@code 갑구 {유효 건수} · 을구 {유효 건수} · {지문}} (예: {@code 갑구 2 · 을구 1 · a1b2c3d4}). 유효는 말소되지
 * 않은 등기다 — 갑구의 소유권 등기는 현재 소유자만 참이다.
 *
 * <p><b>지문을 붙이는 이유</b> — 건수만으로는 소유권 이전 · 근저당 교체처럼 건수가 같은 변동이 전 · 후 같은 문구가 된다. 알림
 * 값이 같으면 중복 방지 키가 같아져 다음 실제 변동이 막힌다. 지문은 갑구 · 을구 전 항목의 의미 필드(순위 · 등기 목적 · 권리자 ·
 * 채무자 · 접수일 · 등기원인 · 금액 · 말소 여부)를 한 줄씩 직렬화해 정렬한 뒤 SHA-256 을 뜬 앞 8자리(16진)다. 정렬하므로 항목
 * 순서와 무관하고, 변동 판정({@code RegistryCommandService})이 보는 필드와 같다.
 *
 * <p>길이 — 건수가 {@code long} 최대 19자리여도 59자라 100자 안이다.
 */
public final class RegistrySummaryCalculator {

    private static final int FINGERPRINT_HEX_LENGTH = 8;
    /** 필드 구분자. 등기 문자열에 나오지 않는 제어 문자(단위 구분자)라 필드 경계가 섞이지 않는다. */
    private static final String FIELD_SEPARATOR = "";

    private RegistrySummaryCalculator() {
    }

    public static String summarize(List<OwnershipEntry> ownerships, List<MortgageEntry> mortgages) {
        long activeOwnerships = ownerships.stream().filter(OwnershipEntry::isActive).count();
        long activeMortgages = mortgages.stream().filter(MortgageEntry::isActive).count();
        return "갑구 " + activeOwnerships + " · 을구 " + activeMortgages + " · " + fingerprint(ownerships, mortgages);
    }

    private static String fingerprint(List<OwnershipEntry> ownerships, List<MortgageEntry> mortgages) {
        String canonical = Stream.concat(
                        ownerships.stream().map(RegistrySummaryCalculator::line),
                        mortgages.stream().map(RegistrySummaryCalculator::line))
                .sorted()
                .reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'), StringBuilder::append)
                .toString();
        return HexFormat.of().formatHex(sha256(canonical)).substring(0, FINGERPRINT_HEX_LENGTH);
    }

    private static String line(OwnershipEntry entry) {
        return join("갑", entry.rankNo(), entry.rightType(), entry.holderName(), entry.receivedDate(), entry.cause(),
                entry.isActive());
    }

    private static String line(MortgageEntry entry) {
        return join("을", entry.rankNo(), entry.rightType(), entry.creditor(), entry.debtorName(),
                entry.receivedDate(), entry.cause(), entry.loanAmount(), entry.maxClaimAmount(),
                entry.priorTenantDeposit(), entry.isActive());
    }

    private static String join(Object... fields) {
        return String.join(FIELD_SEPARATOR, Stream.of(fields).map(String::valueOf).toList());
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // 모든 Java 플랫폼 구현이 SHA-256 을 제공해야 한다(MessageDigest 명세). 여기 오면 런타임이 잘못된 것이다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
