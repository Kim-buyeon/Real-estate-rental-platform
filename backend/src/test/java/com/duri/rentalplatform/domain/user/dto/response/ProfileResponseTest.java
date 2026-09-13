package com.duri.rentalplatform.domain.user.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.user.enums.Role;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProfileResponseTest {

    @Test
    @DisplayName("UTC 로 받은 가입일시를 같은 시각의 서울 오프셋으로 바꾼다")
    void createdAtIsNormalizedToSeoulOffset() {
        OffsetDateTime utc = OffsetDateTime.of(2026, 7, 1, 0, 12, 0, 0, ZoneOffset.UTC);

        ProfileResponse.Account account = new ProfileResponse.Account("홍길동", null, "user@example.com", Role.USER, utc);

        assertThat(account.createdAt()).isEqualTo(OffsetDateTime.of(2026, 7, 1, 9, 12, 0, 0, ZoneOffset.ofHours(9)));
    }
}
