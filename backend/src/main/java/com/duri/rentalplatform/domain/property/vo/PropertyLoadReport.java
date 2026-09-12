package com.duri.rentalplatform.domain.property.vo;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * 적재 결과 집계. 「적재 실패한 항목은 건너뛰고 기록한다」의 기록하는 쪽이다 —
 * 데이터 적재 설계서 1.4.
 *
 * <p>건너뛴 이유를 한 칸에 몰지 않는다. 「주소를 못 찾았다」와 「연동이 죽었다」는 대응이 다르다.
 * 앞은 자료의 문제이고 뒤는 다시 돌리면 된다.
 *
 * <p>record 가 아니라 클래스인 이유는 적재가 도는 동안 누적되기 때문이다. 단일 스레드 실행을
 * 전제하므로 동기화는 두지 않는다.
 */
@Getter
public class PropertyLoadReport {

    /** 실거래 응답으로 받은 건수. */
    private int fetched;

    /** 저장한 건수. */
    private int saved;

    /** 이미 있는 매물이라 건너뛴 건수. 재실행하면 이 값이 곧 전체 건수가 된다. */
    private int skippedDuplicate;

    /** 주소 정규화 결과가 없어 건너뛴 건수. */
    private int skippedAddressNotFound;

    /** 좌표를 얻지 못해 건너뛴 건수. */
    private int skippedCoordinatesNotFound;

    /** 같은 법정동 · 면적대의 전세 표본이 없어 시세를 산출하지 못한 건수. */
    private int skippedMarketPriceNotFound;

    /** 외부 연동이 응답하지 못해 실패한 건수. */
    private int failedExternal;

    /** 실패 사유 목록. 어느 구 · 어느 달에서 무엇이 났는지 남긴다. */
    private final List<String> failures = new ArrayList<>();

    public void addFetched(int count) {
        fetched += count;
    }

    public void addSaved(int count) {
        saved += count;
    }

    public void skipDuplicate() {
        skippedDuplicate++;
    }

    public void skipAddressNotFound() {
        skippedAddressNotFound++;
    }

    public void skipCoordinatesNotFound() {
        skippedCoordinatesNotFound++;
    }

    public void skipMarketPriceNotFound() {
        skippedMarketPriceNotFound++;
    }

    public void failExternal(String reason) {
        failedExternal++;
        failures.add(reason);
    }

    public String summary() {
        return ("받은 %d건 · 저장 %d건 · 중복 %d건 · 주소없음 %d건 · 좌표없음 %d건 · 시세없음 %d건 · 연동실패 %d건")
                .formatted(fetched, saved, skippedDuplicate, skippedAddressNotFound,
                        skippedCoordinatesNotFound, skippedMarketPriceNotFound, failedExternal);
    }
}
