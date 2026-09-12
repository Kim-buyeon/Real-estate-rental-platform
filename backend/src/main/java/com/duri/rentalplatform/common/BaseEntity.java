package com.duri.rentalplatform.common;

import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * 생성일시와 수정일시를 함께 갖는 엔티티의 상위 클래스다.
 *
 * <p>{@code updated_at} 컬럼이 있는 테이블의 엔티티만 이 클래스를 상속한다. 어떤 테이블이 그에 해당하는지는
 * {@link CreatedAtEntity}의 설명을 따른다.
 *
 * <p>{@code @EntityListeners(AuditingEntityListener.class)}를 여기에 다시 붙이지 않는다.
 * 상위 클래스에 선언된 엔티티 리스너는 하위 클래스에 상속되므로 {@link CreatedAtEntity}의 선언만으로
 * 수정일시도 채워진다. 빠진 것이 아니다.
 */
@Getter
@MappedSuperclass
public abstract class BaseEntity extends CreatedAtEntity {

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
