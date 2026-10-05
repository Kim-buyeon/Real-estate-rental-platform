package com.duri.rentalplatform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 설정 파일의 풀 크기 · 세션 시간 제한 · Flyway 연결이 의도대로 묶이는지(#402). 컨텍스트 · DB 없이 설정 파일만 읽어 바인딩한다.
 *
 * <p>읽기용 풀은 {@link ReplicaDataSourceConfig} 와 같은 순서로 만든다 — 기본 풀 설정을 먼저 입히고 읽기용 설정을 덮는다. 그래서
 * 시간 제한을 읽기용 설정에 따로 적지 않아도 같게 가는지가 여기서 드러난다.
 *
 * <p>실제 세션 값({@code SHOW statement_timeout})은 컨테이너가 필요해 통합 테스트({@code DataSourceSessionTimeoutIntegrationTest} ·
 * {@code ReplicaRoutingIntegrationTest})가 본다.
 */
class DataSourceSettingsTest {

    private static final String SESSION_OPTIONS =
            "-c statement_timeout=30s -c idle_in_transaction_session_timeout=60s";

    private Binder binder;

    /** 설정 파일만 넣은 환경. 시스템 환경 변수 · 속성을 빼서 실행 PC 의 값이 섞이지 않게 한다. 자리표시자는 기본값으로 풀린다. */
    @BeforeEach
    void loadApplicationYml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        binder = Binder.get(environment);
    }

    private HikariConfig primary() {
        HikariConfig config = new HikariConfig();
        binder.bind("spring.datasource.hikari", Bindable.ofInstance(config));
        return config;
    }

    @Test
    @DisplayName("기본 풀은 5 이고 세션 시간 제한을 접속 시작 파라미터로 갖는다")
    void primaryPool() {
        HikariConfig primary = primary();
        assertThat(primary.getMaximumPoolSize()).isEqualTo(5);
        assertThat(primary.getPoolName()).isEqualTo("primary");
        assertThat(primary.getDataSourceProperties().getProperty("options")).isEqualTo(SESSION_OPTIONS);
    }

    @Test
    @DisplayName("읽기용 풀은 4 · 읽기 전용이고 세션 시간 제한은 기본 풀에서 그대로 입는다")
    void replicaPoolInheritsSessionOptions() {
        HikariConfig replica = new HikariConfig();
        binder.bind("spring.datasource.hikari", Bindable.ofInstance(replica));
        binder.bind("app.datasource.replica.hikari", Bindable.ofInstance(replica));

        assertThat(replica.getMaximumPoolSize()).isEqualTo(4);
        assertThat(replica.getPoolName()).isEqualTo("replica");
        assertThat(replica.isReadOnly()).isTrue();
        assertThat(replica.getDataSourceProperties().getProperty("options")).isEqualTo(SESSION_OPTIONS);
    }

    @Test
    @DisplayName("Flyway 는 앱과 같은 주소 · 계정의 자기 커넥션을 쓰고 세션 시간 제한을 0 으로 연다")
    void flywayHasOwnConnectionWithoutTimeouts() {
        FlywayProperties flyway = binder.bind("spring.flyway", FlywayProperties.class).get();
        String appUrl = binder.bind("spring.datasource.url", String.class).get();

        // url 이 있어야 Boot 가 앱 풀 대신 풀 없는 데이터 소스를 따로 만든다(FlywayAutoConfiguration.getMigrationDataSource).
        assertThat(flyway.getUrl()).isEqualTo(appUrl);
        assertThat(flyway.getUser()).isEqualTo(binder.bind("spring.datasource.username", String.class).get());
        assertThat(flyway.getPassword()).isEqualTo(binder.bind("spring.datasource.password", String.class).get());
        // url 에 시간 제한을 붙이면 Flyway 커넥션도 그것을 받는다 — 시간 제한은 풀 설정(data-source-properties)에만 있어야 한다.
        assertThat(appUrl).doesNotContain("statement_timeout");
        assertThat(flyway.getInitSqls()).containsExactly(
                "SET statement_timeout = 0;", "SET idle_in_transaction_session_timeout = 0;");
    }
}
