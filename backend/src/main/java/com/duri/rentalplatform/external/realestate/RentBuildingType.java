package com.duri.rentalplatform.external.realestate;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 전월세 실거래가 서비스 구분.
 *
 * <p>국토교통부 전월세 실거래가는 주택 유형마다 <b>서비스가 분리되어 있고 활용 신청도 각각</b>이다.
 * 인증키는 하나(DATA_GO_KR_API_KEY)지만 승인은 서비스 단위로 이루어진다 — 데이터 적재 설계서 1.3.
 *
 * <p>지금 적재에 쓰는 것은 아파트 전월세와 오피스텔 전월세 <b>둘 다</b>다. 연립다세대 · 단독다가구는
 * 별도 서비스이며 이 열거형에 값을 늘리는 시점에 {@code PROPERTY_TYPE} 코드 시드도 함께 늘린다.
 *
 * <p>경로 조각은 제공처 문서의 서비스명이다.
 * 아파트   : https://www.data.go.kr/data/15126474/openapi.do (RTMSDataSvcAptRent)
 * 오피스텔 : https://www.data.go.kr/data/15126475/openapi.do (RTMSDataSvcOffiRent)
 */
@Getter
@RequiredArgsConstructor
public enum RentBuildingType {
    APARTMENT("RTMSDataSvcAptRent", "getRTMSDataSvcAptRent", "MOLIT_RTMS_APT_RENT"),
    OFFICETEL("RTMSDataSvcOffiRent", "getRTMSDataSvcOffiRent", "MOLIT_RTMS_OFFI_RENT");

    private final String servicePath;
    private final String operation;

    /** 응답 출처 표기. 저장 · 응답에서 어느 연동이 준 값인지 식별한다. */
    private final String dataSource;
}
