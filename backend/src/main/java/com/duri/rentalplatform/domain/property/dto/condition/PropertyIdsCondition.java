package com.duri.rentalplatform.domain.property.dto.condition;

import java.util.List;

/**
 * 식별자 목록으로 마커를 조회하는 조건 — 지도 묶음(명세 1.12)에서 한 건뿐인 칸의 매물. 빈 목록이면
 * {@code IN ()} 이 문법 오류이므로 서비스가 호출하지 않는다.
 */
public record PropertyIdsCondition(List<Long> propertyIds) {
}
