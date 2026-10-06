package com.duri.rentalplatform.domain.property.vo;

import com.duri.rentalplatform.domain.property.dto.response.PropertyMapClustersResponse;
import java.time.Instant;

/**
 * 지도 묶음 캐시에 담는 값 — 슬롯 로컬과 Redis 가 같은 형태를 든다. 응답에는 집계 시각이 없어 DB 에서 읽은 시각을 함께 둔다.
 *
 * @param savedAt  DB 에서 읽은 시각. Redis 에서 가져온 값을 로컬에 담을 때 수명을 「이 시각 + Redis TTL」 안으로 깎는 데 쓴다
 * @param response 지도 묶음 응답
 */
public record MapClusterCacheEntry(
        Instant savedAt,
        PropertyMapClustersResponse response
) {
}
