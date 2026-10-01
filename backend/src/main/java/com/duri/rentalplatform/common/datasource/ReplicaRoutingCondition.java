package com.duri.rentalplatform.common.datasource;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 읽기 분산이 켜졌는가 — {@code app.datasource.replica.enabled} 가 true 이고 {@code app.datasource.replica.hosts} 가 비어 있지
 * 않을 때만. 둘 중 하나라도 아니면 읽기용 풀 · 라우팅 · 관점을 만들지 않고 데이터 소스는 Boot 자동 구성 그대로다.
 */
public class ReplicaRoutingCondition extends SpringBootCondition {

    static final String ENABLED = "app.datasource.replica.enabled";
    static final String HOSTS = "app.datasource.replica.hosts";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        ConditionMessage.Builder message = ConditionMessage.forCondition("ReplicaRouting");
        if (!environment.getProperty(ENABLED, Boolean.class, false)) {
            return ConditionOutcome.noMatch(message.because(ENABLED + " 가 true 가 아니다"));
        }
        if (!StringUtils.hasText(environment.getProperty(HOSTS))) {
            return ConditionOutcome.noMatch(message.because(HOSTS + " 가 비었다"));
        }
        return ConditionOutcome.match(message.because("켜짐 · 주소 있음"));
    }
}
