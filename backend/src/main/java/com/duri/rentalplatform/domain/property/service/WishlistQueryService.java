package com.duri.rentalplatform.domain.property.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.CursorCodec;
import com.duri.rentalplatform.common.CursorPage;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.dto.condition.WishlistCondition;
import com.duri.rentalplatform.domain.property.dto.request.WishlistListRequest;
import com.duri.rentalplatform.domain.property.dto.response.WishlistResponse;
import com.duri.rentalplatform.domain.property.mapper.WishlistMapper;
import com.duri.rentalplatform.domain.property.vo.WishlistRow;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관심 매물 목록 — API 명세서(매물) 1.9. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishlistQueryService {

    /** 커서를 만든 정렬. 다른 목록의 커서를 거부하는 서명이다. 식별자만으로 넘기므로 정렬 값은 없다. */
    static final String SORT = "wishId:desc";

    private final WishlistMapper wishlistMapper;

    public CursorPage<WishlistResponse> findByUser(Long userId, WishlistListRequest request) {
        Long lastWishId = null;
        if (request.cursor() != null && !request.cursor().isBlank()) {
            CursorCodec.Cursor cursor = CursorCodec.decode(request.cursor());
            if (!SORT.equals(cursor.s())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
            }
            lastWishId = cursor.id();
        }

        int size = request.size();
        List<WishlistRow> rows = wishlistMapper.selectWishlist(new WishlistCondition(userId, lastWishId, size + 1));
        boolean hasNext = rows.size() > size;
        List<WishlistRow> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = hasNext ? CursorCodec.encode(SORT, null, page.get(page.size() - 1).wishId()) : null;
        return new CursorPage<>(page.stream().map(WishlistResponse::of).toList(), nextCursor, hasNext);
    }
}
