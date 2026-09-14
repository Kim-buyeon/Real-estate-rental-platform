package com.duri.rentalplatform.domain.notification.repository;

import com.duri.rentalplatform.domain.notification.entity.NotificationSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 알림 구독 설정 저장 · 일괄 삭제. 조회는 매퍼가 맡는다 — 아키텍처 설계서(영속성 구조) 1.1. */
public interface NotificationSubscriptionRepository extends JpaRepository<NotificationSubscription, Long> {

    /**
     * 사용자 행 전체를 바로 지운다. 지운 행 수.
     *
     * <p>이름 기반 삭제({@code deleteByUserId})를 쓰지 않는다. 그것은 엔티티를 읽어 삭제를 플러시까지 미루는데,
     * Hibernate 는 플러시에서 INSERT 를 DELETE 보다 먼저 보내므로 같은 트랜잭션의 재삽입이 유일 인덱스
     * (user_id, subscription_type, target_district)에 걸린다. 벌크 DELETE 는 호출 시점에 실행된다.
     */
    @Modifying
    @Query("DELETE FROM NotificationSubscription s WHERE s.userId = :userId")
    int deleteAllByUserIdInBulk(@Param("userId") Long userId);
}
