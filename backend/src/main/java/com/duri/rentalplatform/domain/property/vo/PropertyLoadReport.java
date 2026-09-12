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

    /** 실패 사유를 남기는 최대 건수. 그 뒤로는 계수기만 오른다. */
    private static final int MAX_RECORDED_FAILURES = 100;

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

    /** 자료가 성기거나 어긋나 한 건을 처리하지 못한 건수. 다시 돌려도 같은 건에서 같은 결과다. */
    private int failedInvalidData;

    /** 저장 덩어리 실패 · 자치구 중단처럼 예상하지 못한 자리에서 난 실패 건수. */
    private int failedUnexpected;

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
        record(reason);
    }

    public void failInvalidData(String reason) {
        failedInvalidData++;
        record(reason);
    }

    public void failUnexpected(String reason) {
        failedUnexpected++;
        record(reason);
    }

    public String summary() {
        return ("받은 %d건 · 저장 %d건 · 중복 %d건 · 주소없음 %d건 · 좌표없음 %d건 · 시세없음 %d건"
                + " · 연동실패 %d건 · 자료이상 %d건 · 처리실패 %d건")
                .formatted(fetched, saved, skippedDuplicate, skippedAddressNotFound,
                        skippedCoordinatesNotFound, skippedMarketPriceNotFound, failedExternal,
                        failedInvalidData, failedUnexpected);
    }

    /**
     * 사유를 목록에 남긴다. 앞의 {@link #MAX_RECORDED_FAILURES} 건까지만 담는다.
     *
     * <p>자료 이상은 건 단위로 잡히므로 제공처 응답이 통째로 어긋나면 사유가 수만 건이 된다. 건수는
     * 위 계수기가 전부 세므로 목록은 원인을 알아볼 만큼만 있으면 된다.
     */
    private void record(String reason) {
        if (failures.size() < MAX_RECORDED_FAILURES) {
            failures.add(reason);
            return;
        }
        if (failures.size() == MAX_RECORDED_FAILURES) {
            failures.add("… 실패 사유는 앞의 %d건만 남긴다. 전체 건수는 요약을 본다"
                    .formatted(MAX_RECORDED_FAILURES));
        }
    }
}
