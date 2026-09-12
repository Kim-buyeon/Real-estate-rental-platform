package com.duri.rentalplatform.domain.property.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 매물의 자연키. 같은 매물을 두 번 적재하지 않기 위한 비교 단위다 — 데이터 적재 설계서 1.4
 * 「동일 매물의 중복 적재는 자연키로 차단한다. 재실행해도 결과가 같아야 한다」.
 *
 * <p><b>왜 이 조합인가</b> — 실거래가 API 는 안정적인 거래 식별자를 주지 않는다. 응답 순서도 보장되지
 * 않으므로 순번도 쓸 수 없다. 남는 것은 거래를 특정하는 값들이다. 주소 · 전용면적 · 층까지는 물리적
 * 호실을 좁히고, 보증금 · 월세가 그 호실의 계약을 가른다. 동 · 호는 개인정보보호로 공개되지 않아
 * 더 좁힐 수단이 없다.
 *
 * <p><b>주의</b> — {@code property} 테이블에는 이 조합에 걸린 UNIQUE 제약이 없다. 데이터베이스
 * 설계서 4.3 이 자연 식별자를 가진 엔터티로 네 개만 꼽았고 PROPERTY 는 거기에 없다. 따라서 중복
 * 차단은 <b>애플리케이션이 보장</b>한다. 적재기를 동시에 두 번 돌리면 막히지 않는다 — 일회성 실행을
 * 전제한다. DB 수준 보장이 필요하면 설계서 4.3 을 먼저 고친 뒤 제약을 추가한다.
 *
 * @param address     정규화된 주소
 * @param areaSqm     전용면적(㎡)
 * @param floor       층. 제공처가 비워 보내는 건이 있어 null 을 허용한다
 * @param deposit     보증금(원)
 * @param monthlyRent 월세(원)
 */
public record PropertyNaturalKey(
        String address,
        BigDecimal areaSqm,
        Integer floor,
        Long deposit,
        Long monthlyRent
) {
    /** {@code area_sqm} 은 NUMERIC(7,2) 다. 소수 자릿수가 다르면 같은 면적도 다른 키가 된다. */
    private static final int AREA_SCALE = 2;

    public PropertyNaturalKey {
        areaSqm = areaSqm == null ? null : areaSqm.setScale(AREA_SCALE, RoundingMode.HALF_UP);
    }
}
