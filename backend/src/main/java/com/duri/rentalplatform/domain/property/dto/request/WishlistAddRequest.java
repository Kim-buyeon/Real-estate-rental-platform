package com.duri.rentalplatform.domain.property.dto.request;

import jakarta.validation.constraints.NotNull;

/** {@code POST /api/me/wishlist} 본문 — API 명세서(매물) 1.9. 사용자는 토큰에서 받는다. */
public record WishlistAddRequest(
        @NotNull Long propertyId
) {
}
