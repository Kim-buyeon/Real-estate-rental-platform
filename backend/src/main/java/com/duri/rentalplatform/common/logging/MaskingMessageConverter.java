package com.duri.rentalplatform.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 로그 메시지 본문(%m)을 가린 뒤 출력하는 변환기. {@code logback-spring.xml} 이 {@code %maskedMessage} 로 등록한다.
 *
 * <p>출력 직전 한 곳에서 가린다. 호출하는 쪽이 매번 기억해야 하는 방식은 언젠가 빠뜨린다.
 */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveDataMasker.mask(super.convert(event));
    }
}
