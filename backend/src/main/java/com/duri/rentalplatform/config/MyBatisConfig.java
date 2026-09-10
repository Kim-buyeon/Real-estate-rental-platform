package com.duri.rentalplatform.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 매퍼 인터페이스 등록.
 *
 * <p>매퍼마다 {@code @Mapper} 를 붙이는 방식을 쓰지 않는다. 매퍼는 애노테이션 없는 순수 인터페이스로
 * 두고(패키지 규칙 문서의 Mapper 예시), 등록은 이 한 곳에서 패키지 규칙으로 한다. 애노테이션 방식은
 * 새 매퍼에서 빠뜨렸을 때 컴파일이 통과하고 주입 시점에야 드러난다.
 *
 * <p>빈 정의를 두지 않는다. {@code SqlSessionFactory} 와 {@code SqlSessionTemplate} 은 스타터의
 * 자동설정이 만들고, 설정값은 {@code application.yml} 의 {@code mybatis.*} 가 갖는다. 트랜잭션
 * 매니저도 두지 않는다 — JPA 와 같은 DataSource 를 쓰므로 Spring 이 함께 관리한다(영속성 구조 1.1).
 */
@Configuration
@MapperScan("com.duri.rentalplatform.domain.*.mapper")
public class MyBatisConfig {
}
