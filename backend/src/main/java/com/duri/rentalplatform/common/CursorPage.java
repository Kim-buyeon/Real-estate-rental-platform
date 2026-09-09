package com.duri.rentalplatform.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 커서 기반 목록 응답. API 명세서 §1.2, §1.4를 따른다.
 * 전체 건수는 반환하지 않으며, 마지막 페이지에서는 nextCursor를 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasNext) {
}
