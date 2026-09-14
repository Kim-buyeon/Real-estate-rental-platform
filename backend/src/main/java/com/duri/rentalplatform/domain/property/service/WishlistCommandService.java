package com.duri.rentalplatform.domain.property.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.entity.Wishlist;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관심 매물 등록 · 해제 — API 명세서(매물) 1.9. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WishlistCommandService {

    private static final String FIELD = "propertyId";

    private final PropertyRepository propertyRepository;
    private final WishlistRepository wishlistRepository;

    /** 매물이 없으면 404 {@code PROPERTY_NOT_FOUND}, 이미 등록했으면 409 {@code WISHLIST_DUPLICATED}. */
    @Transactional
    public void add(Long userId, Long propertyId) {
        if (!propertyRepository.existsById(propertyId)) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND, FIELD);
        }
        if (wishlistRepository.existsByUserIdAndPropertyId(userId, propertyId)) {
            throw new BusinessException(ErrorCode.WISHLIST_DUPLICATED, FIELD);
        }
        try {
            // 플러시를 강제해야 제약 위반이 이 자리에서 난다. save 는 INSERT 를 커밋까지 미뤄 catch 를 벗어난다.
            wishlistRepository.saveAndFlush(Wishlist.register(userId, propertyId));
        } catch (DataIntegrityViolationException e) {
            // 두 인스턴스가 같은 등록을 동시에 받으면 둘 다 위 확인을 통과한다. 막는 것은 유일 제약
            // (user_id, property_id)이고, 그대로 두면 500 이 되므로 확인과 같은 409 로 바꾼다.
            log.warn("Wishlist add rejected by a data integrity constraint", e);
            throw new BusinessException(ErrorCode.WISHLIST_DUPLICATED, FIELD);
        }
    }

    /** 해제. 등록되지 않았어도 성공이다 — DELETE 는 멱등이고 결과 상태(등록 안 됨)가 같다. */
    @Transactional
    public void remove(Long userId, Long propertyId) {
        wishlistRepository.deleteByUserIdAndPropertyId(userId, propertyId);
    }
}
