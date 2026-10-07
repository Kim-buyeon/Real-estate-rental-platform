package com.duri.rentalplatform.domain.property.vo;

import com.duri.rentalplatform.domain.property.enums.PriceType;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 이미 적재된 매물 한 건의 자연키 재료와 저장된 시세 — {@code PropertyRepository#findLoadedPricesByDistrict} 의 생성자 투영 행.
 * 갱신 적재(RISK-08)가 자치구 하나를 통째로 읽는 자리라 엔티티 대신 필요한 열만 담는다(#383).
 *
 * <p>평면인 이유 — JPQL 생성자 투영은 한 단계 {@code new} 만 쓴다. 자연키와 스냅샷은 {@link #naturalKey()} ·
 * {@link #snapshot()} 이 나눠 만든다.
 *
 * @param ledgerKeyMissing 건축물대장 조회 키가 비어 있는가. 엔티티의 {@code ledgerKey() == null} 과 같은 판정
 *                         ({@code sigungu_code IS NULL})을 질의가 한다
 */
public record LoadedPriceRow(
        String address,
        BigDecimal areaSqm,
        Integer floor,
        Long deposit,
        Long monthlyRent,
        Long propertyId,
        Long marketPrice,
        PriceType priceType,
        LocalDate priceDate,
        boolean ledgerKeyMissing
) {

    /** 엔티티의 {@code Property#naturalKey} 와 같은 값 — 면적 정규화는 자연키 생성자가 한다. */
    public PropertyNaturalKey naturalKey() {
        return new PropertyNaturalKey(address, areaSqm, floor, deposit, monthlyRent);
    }

    public PropertyPriceSnapshot snapshot() {
        return new PropertyPriceSnapshot(propertyId, marketPrice, priceType, priceDate, ledgerKeyMissing);
    }
}
