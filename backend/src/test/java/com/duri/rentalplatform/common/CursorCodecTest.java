package com.duri.rentalplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

    @Test
    @DisplayName("인코딩한 정렬 값과 식별자가 디코딩으로 그대로 돌아온다")
    void roundTrip() {
        String cursor = CursorCodec.encode("2026-07-20T14:03:00.123456", 1024L);

        CursorCodec.Cursor decoded = CursorCodec.decode(cursor);

        assertThat(decoded.v()).isEqualTo("2026-07-20T14:03:00.123456");
        assertThat(decoded.id()).isEqualTo(1024L);
    }

    @Test
    @DisplayName("커서 문자열은 URL 에 그대로 넣을 수 있는 문자만 쓴다")
    void urlSafe() {
        String cursor = CursorCodec.encode("값?/+=", Long.MAX_VALUE);

        assertThat(cursor).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("Base64 가 아닌 커서는 INVALID_REQUEST")
    void rejectsNonBase64() {
        assertInvalid("!!!not-base64!!!");
    }

    @Test
    @DisplayName("JSON 이 아닌 커서는 INVALID_REQUEST")
    void rejectsNonJson() {
        assertInvalid(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("식별자가 없는 커서는 INVALID_REQUEST")
    void rejectsMissingId() {
        assertInvalid(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"v\":\"1\"}".getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertInvalid(String cursor) {
        assertThatThrownBy(() -> CursorCodec.decode(cursor))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
