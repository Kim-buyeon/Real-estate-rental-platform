package com.duri.rentalplatform.domain.property.repository;

import com.duri.rentalplatform.domain.property.entity.Wishlist;
import org.springframework.data.jpa.repository.JpaRepository;

/** 관심 매물 등록 · 해제. 목록은 매퍼가 맡는다 — 아키텍처 설계서(영속성 구조) 1.1. */
public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    boolean existsByUserIdAndPropertyId(Long userId, Long propertyId);

    /** 지운 행 수. 유일 제약 (user_id, property_id) 로 0 또는 1. */
    long deleteByUserIdAndPropertyId(Long userId, Long propertyId);
}
