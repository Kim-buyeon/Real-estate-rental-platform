package com.duri.rentalplatform.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 오류 코드 단일 관리 지점. API 명세서 §2 오류표와 1:1로 대응한다.
 * 상태 코드는 이 enum이 갖고 전역 처리기가 적용한다. 컨트롤러에서 지정하지 않는다.
 * 상담(CONSULT) 코드는 CONS 영역이 차기 범위이므로 착수 시 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_REQUEST(400, "요청 형식이 올바르지 않습니다."),
    INTERNAL_ERROR(500, "일시적인 오류가 발생했습니다."),

    // 인증
    AUTH_TOKEN_EXPIRED(401, "액세스 토큰이 만료되었습니다."),
    AUTH_INVALID_CREDENTIAL(401, "인증 정보가 일치하지 않습니다."),
    AUTH_FORBIDDEN(403, "접근 권한이 없습니다."),

    // 회원
    USER_DUPLICATED(409, "이미 가입된 계정입니다."),
    PROFILE_INCOMPLETE(422, "대출 한도 계산에 필요한 자격 정보가 없습니다."),

    // 매물
    PROPERTY_NOT_FOUND(404, "존재하지 않는 매물입니다."),
    WISHLIST_DUPLICATED(409, "이미 등록된 관심 매물입니다."),

    // 위험도
    RISK_NOT_ANALYZED(404, "아직 분석되지 않은 매물입니다."),
    RISK_REANALYZE_TOO_SOON(429, "재분석은 잠시 후 다시 요청할 수 있습니다."),

    // 대출
    LOAN_PROPERTY_NOT_ELIGIBLE(422, "보증보험 가입이 불가한 매물입니다."),

    // 외부 연동
    EXTERNAL_API_UNAVAILABLE(503, "일시적으로 조회할 수 없습니다.");

    private final int status;
    private final String message;
}
