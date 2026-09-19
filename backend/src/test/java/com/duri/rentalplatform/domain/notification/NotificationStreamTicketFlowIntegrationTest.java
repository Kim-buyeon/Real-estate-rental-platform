package com.duri.rentalplatform.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 실시간 수신 일회용 티켓의 전 구간 — 발급 → 연결 → 재사용 거부(API 명세서 알림 1.1) — 를 실제 필터체인 · 실제 Redis로
 * 잇는다. 이슈 #125의 4번.
 *
 * <p><b>이 파일이 메우는 공백</b> — {@code StreamTicketStoreTest}는 {@link com.duri.rentalplatform.common.security.StreamTicketStore}
 * 를 실제 Redis에 대고만 보고, {@code NotificationStreamControllerTest}는 그 보관소를 목킹한 채 필터·컨트롤러만 본다.
 * 두 반쪽이 각각 초록이어도 「발급 응답의 티켓이 연결에서 그대로 쓰이는가」, 「같은 티켓의 두 번째 연결이 실제로
 * 401인가」는 어느 쪽도 확인하지 않는다 — 보관소가 목이면 발급·소비가 같은 Redis 값을 가리키는지 알 수 없고,
 * 보관소만 보면 그 값을 실어 나르는 HTTP 왕복(직렬화 · 쿼리 파라미터 · 필터의 소비 시점)이 빠진다.
 *
 * <p>회원은 데이터베이스에 두지 않는다. 인증 필터는 액세스 토큰의 subject를 그대로 인증 주체로 쓸 뿐 회원을
 * 조회하지 않고(JwtAuthenticationFilter), 티켓도 회원 식별자와 토큰 만료만 담을 뿐 존재 확인을 하지 않는다
 * (StreamTicketStore). 확인하는 것은 티켓이 여는 연결 하나이지 회원 도메인이 아니다.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationStreamTicketFlowIntegrationTest {

    private static final long USER_ID = 900_401L;
    private static final String TICKET_PATH = "/api/notifications/stream-ticket";
    private static final String STREAM_PATH = "/api/notifications/stream";

    @Autowired
    MockMvc mockMvc;

    /** 실제 애플리케이션 컨텍스트의 빈이다 — 고정 시크릿을 둔 별도 인스턴스를 만들지 않는다. */
    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("액세스 토큰으로 발급받은 티켓은 연결을 한 번 열고(:connected), 같은 티켓의 두 번째 연결은 401 "
            + "AUTH_INVALID_CREDENTIAL이다")
    void issuedTicketOpensConnectionOnceThenSecondUseIsRejected() throws Exception {
        String accessToken = jwtTokenProvider.createAccessToken(USER_ID, "USER");

        // 1. 발급 — 액세스 토큰으로 인증된 요청만 받는다(API 명세서 알림 1.1).
        String ticket = issueTicket(accessToken);
        assertThat(ticket).isNotBlank();

        // 2. 연결 — 그 티켓을 쿼리 파라미터로 실어 스트림을 연다. 200 · text/event-stream · 연결 직후 주석.
        MvcResult firstConnection = mockMvc.perform(get(STREAM_PATH).queryParam("ticket", ticket))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        // 연결을 여는 SseEmitterStore.open()은 컨트롤러 메서드 안에서 동기로 주석을 보낸다 — 비동기 디스패치를
        // 기다리지 않아도(끝나지 않는 스트림이라 기다릴 수 없다) 첫 청크는 이미 응답에 쓰여 있다.
        assertThat(firstConnection.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .startsWith(":connected");

        // 3. 재사용 — 같은 티켓의 두 번째 연결은 스트림을 열기 전에 공통 봉투로 401을 받는다. 소비는 GETDEL이라
        // 첫 연결이 이미 지운 뒤다.
        mockMvc.perform(get(STREAM_PATH).queryParam("ticket", ticket))
                .andExpect(status().isUnauthorized())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"))
                .andExpect(jsonPath("$.error.message").value("인증 정보가 일치하지 않습니다."));
    }

    @Test
    @DisplayName("발급된 적 없는 티켓으로 연결하면 401 AUTH_INVALID_CREDENTIAL이고 스트림을 열지 않는다")
    void unknownTicketIsRejectedWithoutOpeningStream() throws Exception {
        mockMvc.perform(get(STREAM_PATH).queryParam("ticket", "never-issued-ticket-xyz"))
                .andExpect(status().isUnauthorized())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));
    }

    private String issueTicket(String accessToken) throws Exception {
        ResultActions issued = mockMvc.perform(post(TICKET_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticket").isNotEmpty());
        String body = issued.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.ticket");
    }
}
