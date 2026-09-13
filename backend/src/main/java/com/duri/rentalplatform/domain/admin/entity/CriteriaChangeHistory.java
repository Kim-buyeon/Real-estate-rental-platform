package com.duri.rentalplatform.domain.admin.entity;

import com.duri.rentalplatform.common.CreatedAtEntity;
import com.duri.rentalplatform.domain.admin.enums.CriteriaTarget;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판정 기준 변경 이력 한 행(필드 하나) — 데이터베이스 설계서 31절. 불변 이력이라 변경 메서드가 없다.
 *
 * <p>생성 시각 컬럼 이름이 {@code changed_at} 이라 {@link CreatedAtEntity} 의 {@code createdAt} 을 덮어 맞춘다.
 * 변경자는 식별자로 갖는다 — 이력은 쓰기만 하고, 조회는 매퍼가 이메일을 조인한다.
 */
@Entity
@Getter
@Table(name = "criteria_change_history")
@AttributeOverride(name = "createdAt", column = @Column(name = "changed_at", nullable = false, updatable = false))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CriteriaChangeHistory extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    @Column(nullable = false)
    private UUID changeGroupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_table", nullable = false, length = 30)
    private CriteriaTarget target;

    @Column(nullable = false)
    private Long targetId;

    @Column(nullable = false, length = 80)
    private String targetKey;

    @Column(nullable = false, length = 50)
    private String fieldName;

    @Column(length = 100)
    private String beforeValue;

    @Column(nullable = false, length = 100)
    private String afterValue;

    @Column(nullable = false, length = 200)
    private String changeReason;

    @Column(nullable = false)
    private Long changedBy;

    public static CriteriaChangeHistory of(UUID changeGroupId, CriteriaTarget target, Long targetId, String targetKey,
            String fieldName, String beforeValue, String afterValue, String changeReason, Long changedBy) {
        CriteriaChangeHistory history = new CriteriaChangeHistory();
        history.changeGroupId = changeGroupId;
        history.target = target;
        history.targetId = targetId;
        history.targetKey = targetKey;
        history.fieldName = fieldName;
        history.beforeValue = beforeValue;
        history.afterValue = afterValue;
        history.changeReason = changeReason;
        history.changedBy = changedBy;
        return history;
    }
}
