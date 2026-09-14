package com.duri.rentalplatform.domain.property.repository;

import com.duri.rentalplatform.domain.property.entity.Wishlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 관심 매물 등록 · 해제. 목록은 매퍼가 맡는다 — 아키텍처 설계서(영속성 구조) 1.1. */
public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    boolean existsByUserIdAndPropertyId(Long userId, Long propertyId);

    /** 지운 행 수. 유일 제약 (user_id, property_id) 로 0 또는 1. */
    long deleteByUserIdAndPropertyId(Long userId, Long propertyId);

    /**
     * 사용자의 관심 매물 전체의 모니터링 여부를 한 번에 바꾼다 — 알림 구독 설정(NOTI-01). 바꾼 행 수.
     *
     * <p>영속성 컨텍스트를 우회한다. 같은 트랜잭션에서 이미 읽은 관심 매물 엔티티의 값은 갱신되지 않는다.
     */
    @Modifying
    @Query("UPDATE Wishlist w SET w.monitoring = :monitoring WHERE w.userId = :userId")
    int updateMonitoringByUserId(@Param("userId") Long userId, @Param("monitoring") boolean monitoring);
}
