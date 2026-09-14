package com.duri.rentalplatform.domain.notification.repository;

import com.duri.rentalplatform.domain.notification.entity.WishlistNotification;
import org.springframework.data.jpa.repository.JpaRepository;

/** 관심 매물 모니터링 알림 저장. 목록 조회는 매퍼가 맡는다 — 아키텍처 설계서(영속성 구조) 1.1. */
public interface WishlistNotificationRepository extends JpaRepository<WishlistNotification, Long> {
}
