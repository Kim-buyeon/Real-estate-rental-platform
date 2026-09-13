package com.duri.rentalplatform.domain.property.vo;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 매물 한 건을 저장하기 직전의 값 묶음.
 *
 * <p>엔티티 팩토리에 열다섯 개 인자를 늘어놓지 않기 위해 둔다. 인자가 많아지면 같은 타입끼리 자리가
 * 바뀌어도 컴파일이 통과한다 — 보증금과 시세가 뒤바뀌면 전세가율이 조용히 뒤집힌다.
 *
 * <p>외부 응답이 아니라 <b>정규화와 시세 산출이 끝난</b> 형태다. 주소는 정규화된 값이고 좌표는 이미
 * 확보되어 있다 — 데이터 적재 설계서 1.4.
 *
 * @param contractType 계약 유형. 코드 엔티티를 찾는 열쇠다
 * @param propertyType 매물 유형
 * @param marketPrice  시세(원). 같은 법정동 · 같은 면적대 전세 실거래 보증금의 중앙값
 * @param priceType    시세 산출 근거
 * @param priceDate    시세 기준일. 표본에서 가장 최근 계약일
 */
public record PropertyRegistration(
        String address,
        String district,
        String landlordName,
        ContractType contractType,
        PropertyType propertyType,
        Long deposit,
        Long monthlyRent,
        Long marketPrice,
        PriceType priceType,
        LocalDate priceDate,
        BigDecimal areaSqm,
        Integer floor,
        Integer builtYear,
        BigDecimal latitude,
        BigDecimal longitude
) {
    public PropertyNaturalKey naturalKey() {
        return new PropertyNaturalKey(address, areaSqm, floor, deposit, monthlyRent);
    }
}
