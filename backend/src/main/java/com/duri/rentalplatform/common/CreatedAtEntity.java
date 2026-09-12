package com.duri.rentalplatform.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 생성일시만 갖는 엔티티의 상위 클래스다.
 *
 * <p>수정일시를 함께 두지 않는 이유는 스키마에 있다. 마이그레이션의 테이블 중 {@code updated_at} 컬럼을 가진 것은
 * 외부에서 받아와 주기적으로 덮어쓰는 참조·기준 데이터뿐이고, 나머지 테이블에는 {@code created_at}만 있다.
 * {@code spring.jpa.hibernate.ddl-auto}가 {@code validate}이므로 수정일시를 가진 상위 클래스를 그런 엔티티에
 * 상속시키면 존재하지 않는 컬럼을 요구해 기동이 실패한다. 그래서 상위 클래스를 둘로 나누고, {@code updated_at}이
 * 없는 테이블의 엔티티는 이 클래스를 상속한다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class CreatedAtEntity {

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
