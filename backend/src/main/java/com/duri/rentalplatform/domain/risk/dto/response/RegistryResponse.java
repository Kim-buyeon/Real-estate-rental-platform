package com.duri.rentalplatform.domain.risk.dto.response;

import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.RegistryDataSource;
import com.duri.rentalplatform.domain.risk.vo.RegistryHeaderRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 등기 갑구 · 을구 이력. API 명세서(위험도 분석) 1.3.
 *
 * <p>두 배열은 매퍼가 {@link Ownership} · {@link Mortgage} 를 직접 돌려준 것을 서비스가 묶는다. 중첩 배열은
 * record 생성자 매핑으로 채울 수 없다.
 */
public record RegistryResponse(
        Long propertyId,
        List<Ownership> ownerships,
        List<Mortgage> mortgages,
        OffsetDateTime collectedAt,
        RegistryDataSource dataSource
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 갑구 한 건. 접수일 → 순위번호 순. */
    public record Ownership(
            Integer rankNo,
            OwnershipRightType rightType,
            String holderName,
            LocalDate receivedDate,
            String cause,
            boolean isActive
    ) {
    }

    /** 을구 한 건. 접수일 → 순위번호 순. */
    public record Mortgage(
            Integer rankNo,
            String creditor,
            Long maxClaimAmount,
            LocalDate receivedDate,
            boolean isActive
    ) {
    }

    /** 수집 시각은 서울 오프셋으로 맞춘다 — 공통 규약 1.1 「Asia/Seoul 시간대」. */
    public static RegistryResponse of(RegistryHeaderRow header, List<Ownership> ownerships, List<Mortgage> mortgages) {
        return new RegistryResponse(
                header.propertyId(),
                List.copyOf(ownerships),
                List.copyOf(mortgages),
                header.collectedAt().atZoneSameInstant(SEOUL).toOffsetDateTime(),
                header.dataSource());
    }
}
