package com.duri.rentalplatform.domain.property.dto.response;

import com.duri.rentalplatform.domain.property.vo.LedgerRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * 건축물대장 정보. API 명세서(매물) 1.8.
 *
 * @param isResidential 주용도가 주거용인가. 저장하지 않는다 — 주용도에서 파생되는 값이라 따로 저장하면 둘이 어긋날 수 있다
 */
public record LedgerResponse(
        Long propertyId,
        String mainPurpose,
        boolean isResidential,
        boolean violationBuilding,
        BigDecimal totalFloorArea,
        BigDecimal exclusiveArea,
        LocalDate approvalDate,
        OffsetDateTime collectedAt
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
                RESIDENTIAL_PURPOSES.contains(row.mainPurpose()),
                row.violationBuilding(),
                row.totalFloorArea(),
                row.exclusiveArea(),
                row.approvalDate(),
                row.collectedAt().atZoneSameInstant(SEOUL).toOffsetDateTime());
    }
}
