package com.duri.rentalplatform.external.address;

/**
 * 정규화된 주소. 외부 응답 형태가 아니라 우리가 쓰는 형태다.
 *
 * <p>주소 정규화를 적재 단계에서 하는 이유는 판정에 있다. 등기 주소와 대장 주소의 일치 판정이
 * 정규화된 주소를 전제한다 — 데이터 적재 설계서 1.3 · 1.4. 제공처가 준 문자열을 그대로 저장하면
 * 같은 건물이 표기 차이로 다른 주소가 된다.
 *
 * @param roadAddress   도로명주소. {@code property.address} 에 저장한다
 * @param jibunAddress  지번주소. 등기 · 대장 주소 대조에 쓴다
 * @param district      자치구명(시군구). {@code property.district} 에 저장한다
 * @param legalDongName 법정동명
 * @param zipCode       우편번호
 * @param dataSource    출처 표기
 */
public record NormalizedAddress(
        String roadAddress,
        String jibunAddress,
        String district,
        String legalDongName,
        String zipCode,
        String dataSource
) {
}
