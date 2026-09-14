package com.duri.rentalplatform.domain.property.mapper;

import com.duri.rentalplatform.domain.property.dto.condition.WishlistCondition;
import com.duri.rentalplatform.domain.property.vo.WishlistRow;
import java.util.List;

/** 관심 매물 목록 조회. XML 은 {@code resources/mapper/property/WishlistMapper.xml}. */
public interface WishlistMapper {

    /** 사용자의 관심 매물 한 페이지(요청 크기 + 1). {@code wish_id DESC}. */
    List<WishlistRow> selectWishlist(WishlistCondition condition);
}
