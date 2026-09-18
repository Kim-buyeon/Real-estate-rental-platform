package com.duri.rentalplatform.domain.notification.dto.response;

/**
 * 실시간 수신 연결용 일회용 티켓 발급 응답 — API 명세서(알림) 1.1.
 *
 * <p>티켓 하나만 담는다. 수명을 함께 내려보내지 않는다 — 클라이언트가 티켓을 보관했다가 나중에 쓰는 사용법을 만들 이유가 없다.
 * 받는 즉시 연결에 쓰고, 재연결할 때는 다시 받는다.
 */
public record StreamTicketResponse(String ticket) {}
