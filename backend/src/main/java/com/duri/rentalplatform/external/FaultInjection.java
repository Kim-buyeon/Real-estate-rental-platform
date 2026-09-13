package com.duri.rentalplatform.external;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import java.time.Duration;

/**
 * Fault 구현이 공유하는 장애 주입. 연동마다 같은 코드를 반복하지 않는다.
 *
 * <p>세 가지를 설정으로 고른다 — 즉시 실패, 지연 후 정상, 읽기 타임아웃. 서킷이 열리는지, 열린 동안
 * 폴백이 나가는지, 타임아웃이 설정한 시간 안에 걸리는지를 각각 확인하기 위한 것이다.
 *
 * <p>이 클래스는 테스트 지원용이며 운영 프로파일에서 뜨지 않는다. 어느 구현이 뜨는지는
 * {@code external.<대상>.mode} 가 정하고, 기본값은 mock 이다.
 */
public final class FaultInjection {

    private FaultInjection() {
    }

    /** 즉시 실패. */
    public static final String KIND_ERROR = "error";

    /** 지연 후 정상 응답. 병렬 호출이 순차보다 빠른지 확인할 때 쓴다. */
    public static final String KIND_DELAY = "delay";

    /** 읽기 타임아웃. 지연이 읽기 타임아웃을 넘으면 그 시점에 실패한다. */
    public static final String KIND_TIMEOUT = "timeout";

    /**
     * 설정한 장애를 일으킨다. 정상 응답을 돌려줘야 하는 모드면 그냥 돌아온다.
     *
     * <p>{@link #KIND_TIMEOUT} 은 <b>지연 시간이 아니라 읽기 타임아웃이 지난 시점</b>에 실패한다.
     * 실제 HTTP 연동에서 읽기 타임아웃은 커넥션이 끊는 것이라 제공처가 얼마나 느리든 설정한 시간에
     * 걸린다. 지연 시간만큼 기다린 뒤 던지면 「설정값 안에 예외가 나는가」를 확인할 수 없다 —
     * {@code read-timeout: 5s} 인데 {@code delay: 10s} 면 10초 뒤에 실패한다.
     *
     * @param settings 연동 하나의 설정. 주입 종류 · 지연은 {@code fault}, 타임아웃 기준은 {@code readTimeout}
     */
    public static void inject(ExternalApiProperties.ClientSettings settings) {
        ExternalApiProperties.FaultSettings fault = settings.fault();
        String kind = fault.kind();

        if (KIND_ERROR.equals(kind)) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
        if (KIND_TIMEOUT.equals(kind)) {
            sleep(min(fault.delay(), settings.readTimeout()));
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
        sleep(fault.delay());
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException cause) {
            // 인터럽트를 삼키면 상위의 취소 신호가 사라진다. 상태를 되살리고 실패로 끝낸다.
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
    }
}
