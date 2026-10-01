package com.duri.rentalplatform.domain.risk.enums;

/**
 * 하루 한 번 도는 배치({@link DailyBatch})의 스케줄러 진입점을 한 번 부른 결과. 실패는 여기에 담지 않고 예외로 올린다.
 *
 * <p>참 · 거짓으로 돌려주지 않는 이유 — 부르는 쪽에서 {@code true} 가 「돌렸다」인지 「성공했다」인지 읽히지 않는다. 기동 뒤
 * 따라잡기는 이 값으로 다시 확인할 대상(경합)을 고른다. 예약 실행({@code @Scheduled})에서는 스케줄러가 반환값을 버린다.
 */
public enum DailyBatchRunOutcome {

    /** 날짜 락을 잡아 오늘 회차를 끝까지 돌렸다. */
    RAN,

    /** 다른 인스턴스가 날짜 락을 잡고 있어 건너뛰었다 — 뒤에 다시 확인할 대상이다. */
    CONTENDED
}
