package com.duri.rentalplatform.common.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** enabled · hosts 조합에 따른 켜짐 판정. */
class ReplicaRoutingConditionTest {

    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Conditional(ReplicaRoutingCondition.class)
        String marker() {
            return "on";
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    private boolean check(String... props) {
        boolean[] result = new boolean[1];
        runner.withPropertyValues(props).run(ctx -> result[0] = ctx.containsBean("marker"));
        return result[0];
    }

    @Test
    @DisplayName("기본(아무것도 없음)은 꺼짐")
    void defaultOff() {
        assertThat(check()).isFalse();
    }

    @Test
    @DisplayName("enabled=true 이고 hosts 가 있으면 켜짐")
    void enabledWithHosts() {
        assertThat(check("app.datasource.replica.enabled=true", "app.datasource.replica.hosts=a:5432,b:5432"))
                .isTrue();
    }

    @Test
    @DisplayName("enabled=true 라도 hosts 가 비면 꺼짐")
    void enabledWithoutHosts() {
        assertThat(check("app.datasource.replica.enabled=true", "app.datasource.replica.hosts=")).isFalse();
        assertThat(check("app.datasource.replica.enabled=true", "app.datasource.replica.hosts=   ")).isFalse();
        assertThat(check("app.datasource.replica.enabled=true")).isFalse();
    }

    @Test
    @DisplayName("hosts 가 있어도 enabled 가 false 이거나 없으면 꺼짐")
    void hostsWithoutEnabled() {
        assertThat(check("app.datasource.replica.enabled=false", "app.datasource.replica.hosts=a:5432")).isFalse();
        assertThat(check("app.datasource.replica.hosts=a:5432")).isFalse();
    }
}
