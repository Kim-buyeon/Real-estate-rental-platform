package com.duri.rentalplatform.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전역 예외 처리기가 각 예외를 API 명세서 §1.2 봉투로 변환하는지 확인한다.
 *
 * <p>필터를 꺼서 인증·인가를 배제한다. 필터체인이 만드는 응답은 {@code SecurityConfigTest}가 따로 본다.
 *
 * <p>슬라이스에는 운영 컨트롤러를 올리지 않는다. {@code controllers}로 아래 {@link TestController}만 지정해
 * 스캔 범위를 묶어 둔다. 여기서 보는 것은 처리기의 변환이지 특정 컨트롤러의 동작이 아니므로 예외를 던지는 시험용
 * 엔드포인트로 충분하고, 운영 컨트롤러를 끌어들이면 그 컨트롤러의 서비스 의존까지 이 슬라이스에 올려야 한다.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.TestController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("업무 예외는 ErrorCode의 상태·코드로 실패 봉투를 반환한다")
    void businessException() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PROPERTY_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("존재하지 않는 매물입니다."))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.field").doesNotExist());
    }

    @Test
    @DisplayName("업무 예외의 field는 응답 error.field에 담긴다")
    void businessExceptionWithField() throws Exception {
        mockMvc.perform(get("/test/business-field"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("PROFILE_INCOMPLETE"))
                .andExpect(jsonPath("$.error.field").value("annualIncome"));
    }

    @Test
    @DisplayName("요청 본문 검증 실패는 400 INVALID_REQUEST와 위반 필드를 반환한다")
    void validationFailure() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("name"));
    }

    @Test
    @DisplayName("예상하지 못한 예외는 500 INTERNAL_ERROR로 변환하고 내부 메시지를 노출하지 않는다")
    void unexpectedException() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("일시적인 오류가 발생했습니다."))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("보안상 노출 금지 내부 사유"))));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/business")
        void business() {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }

        @GetMapping("/test/business-field")
        void businessField() {
            throw new BusinessException(ErrorCode.PROFILE_INCOMPLETE, "annualIncome");
        }

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody SampleRequest request) {
            // 검증 통과 시 도달. 본문 없음.
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("보안상 노출 금지 내부 사유");
        }

        record SampleRequest(@NotBlank String name) {
        }
    }
}
