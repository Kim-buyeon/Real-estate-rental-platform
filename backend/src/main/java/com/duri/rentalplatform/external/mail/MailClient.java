package com.duri.rentalplatform.external.mail;

/**
 * 메일 발송 연동. 지금 보내는 메일은 비밀번호 재설정 링크 하나다(USER-06) — 기술 스택 정의서 2장.
 *
 * <p>구현은 셋이다 — Mock(링크를 로그로) · Real(SMTP) · Fault(지연 · 오류 주입).
 * 어느 구현이 뜨는지는 {@code external.mail.mode} 설정이 정한다. 기본은 mock 이라 SMTP 계정 없이 돈다.
 *
 * <p>호출은 트랜잭션 밖 비동기에서 한다. 실패는 {@code BusinessException(EXTERNAL_API_UNAVAILABLE)} 으로 올라오며 호출자가 기록하고
 * 삼킨다 — 요청 응답은 이미 나갔다.
 */
public interface MailClient {

    /**
     * Resilience4j 인스턴스 이름. {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances} 키와 같아야 한다.
     * 구현이 아니라 인터페이스가 갖는 이유는 다른 연동과 같다 — Real 과 Fault 가 같은 서킷을 쓴다.
     *
     * <p>재시도는 걸지 않는다. 발송은 조회가 아니라 멱등하지 않다 — SMTP 가 받고 응답만 늦으면 같은 메일이 두 통 간다.
     */
    String RESILIENCE_INSTANCE = "mail";

    /**
     * 비밀번호 재설정 링크를 보낸다.
     *
     * @param recipient 받는 주소. 비밀번호 계정의 이메일
     * @param link      토큰이 실린 화면 링크. 토큰 원문이 들어 있으므로 구현은 이 값을 로그에 남기지 않는다(Mock 제외)
     */
    void sendPasswordResetLink(String recipient, String link);
}
