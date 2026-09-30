package com.duri.rentalplatform.domain.property.dto.response;

import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.vo.LedgerRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * 건축물대장 정보. API 명세서(매물) 1.8.
 *
 * <p><b>null 은 「확인 불가」</b>다. 대장이 그 항목을 주지 않거나(건축HUB 의 위반건축물), 뗄 대장이 없을 때(조회 키 없음 ·
 * 필지에 맞는 표제부 없음)다. 뗄 대장이 없으면 {@code propertyId} 만 채우고 나머지는 전부 null 이다 — 공통 규약 2장에 「대장
 * 없음」에 맞는 오류 코드가 없어 200 으로 낸다.
 *
 * @param isResidential 주용도가 주거용인가. 저장하지 않는다 — 주용도에서 파생되는 값이라 따로 저장하면 둘이 어긋날 수 있다.
 *                      주용도가 없으면(대장 없음) null
 * @param dataSource    대장 수집 출처. 뗄 대장이 없으면 null
 */
public record LedgerResponse(
        Long propertyId,
        String mainPurpose,
        Boolean isResidential,
        Boolean violationBuilding,
        BigDecimal totalFloorArea,
        BigDecimal exclusiveArea,
        LocalDate approvalDate,
        OffsetDateTime collectedAt,
        LedgerDataSource dataSource
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 주거용 주용도. 건축법 시행령 별표 1 이 주거용으로 두는 것은 제1호 단독주택 · 제2호 공동주택이다. 오피스텔은
     * 주거에 쓰이더라도 제14호 업무시설이라 여기에 들지 않는다.
     */
    private static final Set<String> RESIDENTIAL_PURPOSES = Set.of("단독주택", "공동주택");

    /** 주거용 여부를 주용도에서 만들고 수집 시각을 서울 오프셋으로 맞춘다 — 공통 규약 1.1 「Asia/Seoul 시간대」. */
    public static LedgerResponse of(LedgerRow row) {
        return new LedgerResponse(
                row.propertyId(),
                row.mainPurpose(),
                row.mainPurpose() == null ? null : RESIDENTIAL_PURPOSES.contains(row.mainPurpose()),
                row.violationBuilding(),
                row.totalFloorArea(),
                row.exclusiveArea(),
                row.approvalDate(),
                row.collectedAt() == null ? null : row.collectedAt().atZoneSameInstant(SEOUL).toOffsetDateTime(),
                row.dataSource());
    }
}
