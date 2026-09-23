package com.duri.rentalplatform.common.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * 예외 출력(Spring Boot 기본 {@code %wEx})을 가린 뒤 내보내는 변환기. {@code logback-spring.xml} 이
 * {@code %maskedWex} 로 등록한다.
 *
 * <p>메시지 변환기만으로는 부족하다. 민감한 값은 예외 메시지에도 실린다 — 파라미터를 담아 던지는 영속성 계층의
 * 예외가 그렇고, 그 문자열은 {@code %m} 이 아니라 예외 변환기를 거쳐 나간다.
 *
 * <p>Spring Boot 기본 동작(확장 스택트레이스 + 앞뒤 공백)을 그대로 쓰고 결과 문자열만 가린다. 기본을 다시 구현하면
 * Boot 가 바뀔 때 조용히 달라진다.
 */
public class MaskingThrowableProxyConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return SensitiveDataMasker.mask(super.throwableProxyToString(tp));
    }
}
