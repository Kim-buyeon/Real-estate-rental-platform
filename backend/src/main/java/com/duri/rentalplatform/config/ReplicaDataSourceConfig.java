package com.duri.rentalplatform.config;

import com.duri.rentalplatform.common.datasource.ReplicaRoutingCondition;
import com.duri.rentalplatform.common.datasource.ReplicaRoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * 읽기 분산(INF-03 확장, #343). 켜졌을 때만 적용된다({@link ReplicaRoutingCondition}). 꺼져 있으면 이 설정이 통째로 빠지고 데이터
 * 소스는 Boot 자동 구성의 Hikari 하나다 — 지금까지와 같다.
 *
 * <p><b>켜졌을 때의 구성</b>
 * <ul>
 *   <li>{@code primaryDataSource} — 기본 풀. 자동 구성의 Hikari 와 같은 재료({@code spring.datasource.*} · 연결 정보 ·
 *       {@code spring.datasource.hikari.*})로 만든다. 이 설정이 {@code DataSource} 빈을 정의하면 자동 구성이 물러나므로 대신
 *       만든다.</li>
 *   <li>{@code replicaDataSource} — 읽기용 풀. 기본 풀 설정({@code spring.datasource.hikari.*})을 먼저 입히고 그 위에
 *       {@code app.datasource.replica.hikari.*}(풀 이름 · 읽기 전용 · 크기)를 덮는다.</li>
 *   <li>{@code dataSource} — 두 풀을 고르는 {@link ReplicaRoutingDataSource} 를 {@link LazyConnectionDataSourceProxy} 로 감싼
 *       것. 앱이 쓰는 유일한 기본 후보다 — JPA · MyBatis · 트랜잭션 매니저 · 배치가 이것 하나를 쓴다(영속성 구조 1.1, 트랜잭션
 *       매니저 하나).</li>
 * </ul>
 *
 * <p>두 풀은 Spring Boot 문서(데이터 소스 둘 구성)대로 {@code @Bean(defaultCandidate = false)} + {@code @Qualifier} 다. 한정자
 * 없이 {@code DataSource} 를 받는 곳에는 끼지 않는다. Hikari 지표는 기본 후보가 아닌 빈까지 묶으므로(Boot 4.1.1
 * {@code DataSourcePoolMetricsAutoConfiguration} 이 {@code ObjectProvider.UNFILTERED} 로 고른다) {@code pool} 태그
 * primary · replica 로 나뉘어 나온다.
 *
 * <p><b>Flyway 는 어느 풀도 쓰지 않는다</b>(#402). {@code spring.flyway.url} 이 기본 풀과 같은 주소(쓰기 노드)를 가리키고, Boot 가
 * 그 주소로 풀 없는 데이터 소스를 따로 만든다 — 그래서 여기서 Flyway 전용 데이터 소스를 지정하지 않는다. 지정하면 Boot 는 그것을
 * 먼저 쓰므로({@code FlywayAutoConfiguration.getMigrationDataSource}) Flyway 가 기본 풀을 빌린다. 그러면 풀의 세션 시간 제한
 * ({@code spring.datasource.hikari.data-source-properties})이 마이그레이션에 걸리고, 그것을 풀려고 SET 한 세션은 풀로 돌아간다.
 * 마이그레이션이 라우팅을 거치지 않는 것(쓰기 노드에서 돈다는 것을 「표시가 없다」는 간접 조건에 걸지 않는 것)은 그대로다.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(ReplicaRoutingCondition.class)
public class ReplicaDataSourceConfig {

    private static final String PRIMARY_HIKARI = "spring.datasource.hikari";

    /**
     * 기본 풀. 연결 정보는 {@link JdbcConnectionDetails} 빈이 있으면 그것(테스트의 {@code @ServiceConnection} 등), 없으면
     * {@code spring.datasource.*} 다 — 자동 구성이 하던 순서와 같다. 풀 설정은 {@code spring.datasource.hikari.*} 가 덮는다.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(ReplicaRoutingDataSource.PRIMARY)
    @ConfigurationProperties(PRIMARY_HIKARI)
    HikariDataSource primaryDataSource(DataSourceProperties properties,
            ObjectProvider<JdbcConnectionDetails> connectionDetailsProvider) {
        DataSourceBuilder<HikariDataSource> builder =
                DataSourceBuilder.create(properties.getClassLoader()).type(HikariDataSource.class);
        JdbcConnectionDetails connectionDetails = connectionDetailsProvider.getIfAvailable();
        if (connectionDetails != null) {
            builder.url(connectionDetails.getJdbcUrl())
                    .username(connectionDetails.getUsername())
                    .password(connectionDetails.getPassword())
                    .driverClassName(connectionDetails.getDriverClassName());
        } else {
            builder.url(properties.determineUrl())
                    .username(properties.determineUsername())
                    .password(properties.determinePassword())
                    .driverClassName(properties.determineDriverClassName());
        }
        return builder.build();
    }

    /**
     * 읽기용 풀. 기본 풀 설정을 먼저 입힌다 — 접속 옵션({@code data-source-properties} — 세션 시간 제한(#402)이 여기 있다) ·
     * 대여 시간 상한 · 누수 감지를 따로 적지 않아도 같게 간다. 그 뒤 {@code app.datasource.replica.hikari.*} 가 풀 이름 · 읽기 전용 · 크기를
     * 덮는다(메서드가 돌고 난 뒤 {@link ConfigurationProperties} 바인딩이 적용된다).
     */
    @Bean(defaultCandidate = false)
    @Qualifier(ReplicaRoutingDataSource.REPLICA)
    @ConfigurationProperties("app.datasource.replica.hikari")
    HikariDataSource replicaDataSource(DataSourceProperties properties, Environment environment,
            @Value("${app.datasource.replica.url}") String url,
            @Value("${app.datasource.replica.username}") String username,
            @Value("${app.datasource.replica.password}") String password) {
        HikariDataSource dataSource = DataSourceBuilder.create(properties.getClassLoader())
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
        Binder.get(environment).bind(PRIMARY_HIKARI, Bindable.ofInstance(dataSource));
        return dataSource;
    }

    /**
     * 앱의 데이터 소스. 지연 프록시가 트랜잭션 시작 때 커넥션을 잡지 않고 첫 조회 때 라우팅에 묻는다. 관점이 트랜잭션 바깥에서
     * 표시를 걸므로 지금은 시작 시점에 골라도 결과가 같지만, 순서가 바뀌어도 첫 조회 시점의 표시로 고르게 한다.
     */
    @Bean
    DataSource dataSource(@Qualifier(ReplicaRoutingDataSource.PRIMARY) DataSource primary,
            @Qualifier(ReplicaRoutingDataSource.REPLICA) DataSource replica) {
        ReplicaRoutingDataSource routing = new ReplicaRoutingDataSource();
        routing.setTargetDataSources(Map.of(
                ReplicaRoutingDataSource.PRIMARY, primary,
                ReplicaRoutingDataSource.REPLICA, replica));
        routing.setDefaultTargetDataSource(primary);
        routing.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routing);
    }
}
