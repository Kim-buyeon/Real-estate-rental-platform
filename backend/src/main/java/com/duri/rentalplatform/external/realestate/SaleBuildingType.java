package com.duri.rentalplatform.external.realestate;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 매매 실거래가 서비스 구분.
 *
 * <p>전월세와 같이 주택 유형마다 <b>서비스가 분리되어 있고 활용 신청도 각각</b>이다. 인증키는 같다(DATA_GO_KR_API_KEY).
 * 값은 전월세 서비스 구분({@link RentBuildingType})과 짝을 맞춘다 — 매물이 되는 유형의 시세표를 만드는 자료이므로 유형을
 * 늘릴 때 두 열거형을 함께 늘린다.
 *
 * <p>제공처 문서 — 아파트 매매 상세 https://www.data.go.kr/data/15126468/openapi.do,
 * 오피스텔 매매 https://www.data.go.kr/data/15126464/openapi.do.
 *
 * <p>경로 조각은 제공처의 서비스명 · 오퍼레이션명이다(2026-09-30 실호출로 응답 확인).
 * 아파트 매매 상세 : https://apis.data.go.kr/1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev
 * 오피스텔 매매   : https://apis.data.go.kr/1613000/RTMSDataSvcOffiTrade/getRTMSDataSvcOffiTrade
 */
@Getter
@RequiredArgsConstructor
public enum SaleBuildingType {
    APARTMENT("RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev", "MOLIT_RTMS_APT_TRADE"),
    OFFICETEL("RTMSDataSvcOffiTrade", "getRTMSDataSvcOffiTrade", "MOLIT_RTMS_OFFI_TRADE");

    private final String servicePath;
    private final String operation;

    /** 응답 출처 표기. 어느 연동이 준 값인지 식별한다. */
    private final String dataSource;
}
