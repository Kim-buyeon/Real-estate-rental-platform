package com.duri.rentalplatform.domain.loan.vo;

import com.duri.rentalplatform.domain.property.enums.PropertyType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 대출 상품 금리 갱신 한 회차의 집계. 매물 유형마다 반영 · 실적 없음 · 연동 실패 중 하나로 끝난다.
 *
 * <p>실적 없음과 연동 실패는 둘 다 기존 행을 그대로 둔다. 둘을 가르는 이유는 원인이 달라서다 — 앞은 제공처가 그 달을 아직 집계하지 않은
 * 것이고, 뒤는 외부 장애다.
 */
public class LoanProductRefreshReport {

    private final List<PropertyType> refreshed = new ArrayList<>();
    private final List<PropertyType> empty = new ArrayList<>();
    private final List<PropertyType> failed = new ArrayList<>();
    private int inserted;
    private int updated;
    private int unchanged;

    public void refreshed(PropertyType houseType, LoanProductWriteResult result) {
        refreshed.add(houseType);
        inserted += result.inserted();
        updated += result.updated();
        unchanged += result.unchanged();
    }

    public void empty(PropertyType houseType) {
        empty.add(houseType);
    }

    public void failed(PropertyType houseType) {
        failed.add(houseType);
    }

    public List<PropertyType> refreshed() {
        return Collections.unmodifiableList(refreshed);
    }

    public List<PropertyType> empty() {
        return Collections.unmodifiableList(empty);
    }

    public List<PropertyType> failed() {
        return Collections.unmodifiableList(failed);
    }

    public int inserted() {
        return inserted;
    }

    public int updated() {
        return updated;
    }

    public int unchanged() {
        return unchanged;
    }

    public String summary() {
        return "반영 %s · 실적 없음 %s · 연동 실패 %s · 추가 %d · 갱신 %d · 유지 %d"
                .formatted(refreshed, empty, failed, inserted, updated, unchanged);
    }
}
