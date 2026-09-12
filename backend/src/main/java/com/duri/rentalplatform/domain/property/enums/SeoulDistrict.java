package com.duri.rentalplatform.domain.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 서울시 25개 자치구와 법정동 코드 앞 5자리(행정표준코드의 시군구 코드).
 *
 * <p>국토교통부 전월세 실거래가는 이 5자리를 {@code LAWD_CD} 로 받는다. 적재 대상 범위가 서울 25개
 * 자치구이므로 목록을 코드에 고정한다 — 구 목록은 제도 변경이 없는 한 바뀌지 않으며, 설정으로 빼면
 * 오타가 조용히 0건 적재로 나타난다.
 */
@Getter
@RequiredArgsConstructor
public enum SeoulDistrict {
    JONGNO("11110", "종로구"),
    JUNG("11140", "중구"),
    YONGSAN("11170", "용산구"),
    SEONGDONG("11200", "성동구"),
    GWANGJIN("11215", "광진구"),
    DONGDAEMUN("11230", "동대문구"),
    JUNGNANG("11260", "중랑구"),
    SEONGBUK("11290", "성북구"),
    GANGBUK("11305", "강북구"),
    DOBONG("11320", "도봉구"),
    NOWON("11350", "노원구"),
    EUNPYEONG("11380", "은평구"),
    SEODAEMUN("11410", "서대문구"),
    MAPO("11440", "마포구"),
    YANGCHEON("11470", "양천구"),
    GANGSEO("11500", "강서구"),
    GURO("11530", "구로구"),
    GEUMCHEON("11545", "금천구"),
    YEONGDEUNGPO("11560", "영등포구"),
    DONGJAK("11590", "동작구"),
    GWANAK("11620", "관악구"),
    SEOCHO("11650", "서초구"),
    GANGNAM("11680", "강남구"),
    SONGPA("11710", "송파구"),
    GANGDONG("11740", "강동구");

    /** 법정동 코드 앞 5자리. 실거래가 API 의 LAWD_CD 파라미터 값이다. */
    private final String lawdCode;

    /** 자치구명. {@code property.district} 에 그대로 저장한다 — 규약 문서 도메인 용어 {@code district}. */
    private final String districtName;
}
