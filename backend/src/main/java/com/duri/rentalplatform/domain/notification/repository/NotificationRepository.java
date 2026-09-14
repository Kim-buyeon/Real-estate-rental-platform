package com.duri.rentalplatform.domain.notification.repository;

import com.duri.rentalplatform.domain.notification.entity.Notification;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 알림 공통 행 저장 · 읽음 변경. 목록 조회는 매퍼가 맡는다 — 아키텍처 설계서(영속성 구조) 1.1. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 본인 알림만. 남의 알림은 없는 것과 같다. */
    Optional<Notification> findByNotifIdAndUserId(Long notifId, Long userId);

    /**
     * 사용자의 읽지 않은 알림을 한 문장으로 읽음 처리한다. 영속성 컨텍스트를 거치지 않으므로 실행 뒤 같은 트랜잭션의 엔티티 상태를
     * 믿지 않는다(backend/CLAUDE.md Repository). 읽지 않은 행 부분 인덱스(V14)를 탄다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.read = true WHERE n.userId = :userId AND n.read = false")
    int markAllReadByUserId(@Param("userId") Long userId);
}
