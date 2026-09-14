package com.duri.rentalplatform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 반복 실행 배치를 켠다. 앱이 두 프로세스로 뜨므로 모든 스케줄이 두 번 호출된다 — 한 번만 실행되는 것은 각 배치가 분산 락으로
 * 보장한다. 개별 스케줄을 끄는 설정은 각 스케줄 빈이 갖는다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
