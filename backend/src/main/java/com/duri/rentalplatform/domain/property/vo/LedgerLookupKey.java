package com.duri.rentalplatform.domain.property.vo;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 건축물대장 조회 키. 건축HUB 건축물대장정보 서비스의 요청 파라미터 {@code sigunguCd} · {@code bjdongCd} · {@code bun} ·
 * {@code ji} 와 같은 값이며 {@code property} 의 네 컬럼에 저장한다.
 *
 * @param sigunguCode 시군구 코드 5자리
 * @param bjdongCode  법정동 코드 5자리(10자리 법정동 코드의 뒤 5자리)
 * @param bun         본번 4자리. 앞을 0 으로 채운다
 * @param ji          부번 4자리. 부번이 없으면 {@code 0000}
 */
public record LedgerLookupKey(
        String sigunguCode,
        String bjdongCode,
        String bun,
        String ji
) {

    private static final Pattern SIGUNGU_CODE = Pattern.compile("\\d{5}");
    private static final Pattern LEGAL_DONG_CODE = Pattern.compile("\\d{10}");

    /** 본번 또는 본번-부번. 산 지번 · 블록 · 로트 표기 · 세 마디 이상은 형식 밖이다. */
    private static final Pattern JIBUN = Pattern.compile("(\\d{1,4})(?:-(\\d{1,4}))?");

    private static final int SIGUNGU_LENGTH = 5;
    private static final String NO_SUB_NUMBER = "0";

    /**
     * 적재 때 받은 값으로 조회 키를 만든다. 하나라도 형식 밖이면 빈 값이다 — 추측으로 채운 키는 다른 건물의 대장을 떼어 온다.
     *
     * <ul>
     *   <li>시군구 코드는 전월세 실거래 조회 요청의 지역 코드(LAWD_CD), 법정동 코드는 도로명주소 검색 응답의 10자리 코드다. 법정동 코드의 앞
     *       5자리가 시군구 코드와 다르면 두 응답이 다른 곳을 가리킨 것이라 빈 값이다.</li>
     *   <li>지번은 {@code "200-16"} → {@code 0200} · {@code 0016}, {@code "702"} → {@code 0702} · {@code 0000}.
     *       산 지번({@code "산12"})은 대장 조회에 대지구분 파라미터가 따로 필요해 이 키로 표현하지 않는다.</li>
     * </ul>
     *
     * @param sigunguCode   시군구 코드 5자리
     * @param legalDongCode 법정동 코드 10자리
     * @param jibun         지번 원문
     */
    public static Optional<LedgerLookupKey> of(String sigunguCode, String legalDongCode, String jibun) {
        if (sigunguCode == null || legalDongCode == null || jibun == null) {
            return Optional.empty();
        }
        String sigungu = sigunguCode.strip();
        String legalDong = legalDongCode.strip();
        if (!SIGUNGU_CODE.matcher(sigungu).matches() || !LEGAL_DONG_CODE.matcher(legalDong).matches()
                || !legalDong.startsWith(sigungu)) {
            return Optional.empty();
        }
        Matcher lot = JIBUN.matcher(jibun.strip());
        if (!lot.matches()) {
            return Optional.empty();
        }
        String sub = lot.group(2) == null ? NO_SUB_NUMBER : lot.group(2);
        return Optional.of(new LedgerLookupKey(
                sigungu, legalDong.substring(SIGUNGU_LENGTH), pad(lot.group(1)), pad(sub)));
    }

    private static String pad(String number) {
        return "%04d".formatted(Integer.parseInt(number));
    }
}
