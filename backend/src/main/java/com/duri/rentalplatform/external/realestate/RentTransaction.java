package com.duri.rentalplatform.external.realestate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 전월세 실거래 1건. <b>외부 응답 형태가 아니라 우리가 쓰는 형태</b>다.
 *
 * <p>외부 응답의 이름과 단위를 그대로 끌고 오지 않는다 — 제공처는 금액을 만원 단위 문자열(콤마 포함)로,
 * 계약일을 연 · 월 · 일 세 필드로 준다. 변환은 각 구현이 끝내고 이 레코드는 원 단위 {@code Long} 과
 * {@link LocalDate} 만 갖는다.
 *
 * @param lawdCode       법정동 코드 앞 5자리
 * @param legalDongName  법정동명. 시세 중앙값을 묶는 단위다
 * @param buildingName   단지 · 건물명
 * @param jibun          지번
 * @param areaSqm        전용면적(㎡)
 * @param floor          층. 제공처가 비워 보내는 건이 있어 null 을 허용한다
 * @param deposit        보증금(원)
 * @param monthlyRent    월세(원). 전세는 0
 * @param contractDate   계약일
 * @param buildYear      건축연도. 제공처가 비워 보내는 건이 있어 null 을 허용한다
 * @param buildingType   어느 서비스에서 왔는가
 * @param dataSource     출처 표기
 */
public record RentTransaction(
        String lawdCode,
        String legalDongName,
        String buildingName,
        String jibun,
        BigDecimal areaSqm,
        Integer floor,
        Long deposit,
        Long monthlyRent,
        LocalDate contractDate,
        Integer buildYear,
        RentBuildingType buildingType,
        String dataSource
) {
}
