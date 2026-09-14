package com.duri.rentalplatform.domain.property.mapper;

import com.duri.rentalplatform.domain.property.dto.condition.WishlistCondition;
import com.duri.rentalplatform.domain.property.dto.condition.WishlistedPropertyCondition;
import com.duri.rentalplatform.domain.property.vo.WishlistRow;
import java.util.List;

/** 관심 매물 목록 조회. XML 은 {@code resources/mapper/property/WishlistMapper.xml}. */
public interface WishlistMapper {

    /** 사용자의 관심 매물 한 페이지(요청 크기 + 1). {@code wish_id DESC}. */
    List<WishlistRow> selectWishlist(WishlistCondition condition);

    /**
     * 누군가 관심 매물로 등록한 서로 다른 매물 식별자 한 페이지. {@code property_id ASC}. 배치용 대량 조회다 — 아키텍처
     * 설계서(영속성 구조) 1.1. 모니터링 여부와 무관하게 전부 담는다.
     */
    List<Long> selectWishlistedPropertyIds(WishlistedPropertyCondition condition);
}
