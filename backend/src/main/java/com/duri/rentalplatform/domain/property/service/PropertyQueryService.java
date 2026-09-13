package com.duri.rentalplatform.domain.property.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.CursorCodec;
import com.duri.rentalplatform.common.CursorPage;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.calculator.GeoDistanceCalculator;
import com.duri.rentalplatform.domain.property.dto.condition.PropertyDetailCondition;
import com.duri.rentalplatform.domain.property.dto.condition.PropertySearchCondition;
import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.request.PropertySearchRequest;
import com.duri.rentalplatform.domain.property.dto.response.DistrictCountsResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyDetailResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyListResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkerResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkersResponse;
import com.duri.rentalplatform.domain.property.enums.PropertySortKey;
import com.duri.rentalplatform.domain.property.mapper.PropertyMapper;
import com.duri.rentalplatform.domain.property.store.DistrictCountCacheStore;
import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import com.duri.rentalplatform.domain.property.vo.PropertyDetailRow;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매물 조회. API 명세서(매물) 1.4 ~ 1.7. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PropertyQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 전세가율 정렬에서 미분석 매물의 대체값. {@code lease_ratio} 는 NUMERIC(5,2) 라 -999.99 ~ 999.99 다.
     * 오름차순이면 최댓값보다 크게, 내림차순이면 최솟값보다 작게 두어 방향과 무관하게 맨 뒤로 보낸다.
     */
    static final BigDecimal NULL_DEBT_RATIO_ASC = new BigDecimal("1000");
    static final BigDecimal NULL_DEBT_RATIO_DESC = new BigDecimal("-1000");

    private static final PropertySortKey DEFAULT_SORT_KEY = PropertySortKey.REGISTERED_AT;

    private final PropertyMapper propertyMapper;
    private final DistrictCountCacheStore districtCountCacheStore;

    /** 자치구 집계. 같은 필터 조합은 캐시에서 돌려준다. */
    public DistrictCountsResponse getDistrictCounts(DistrictCountRequest filter) {
        return districtCountCacheStore.find(filter).orElseGet(() -> {
            DistrictCountsResponse response = DistrictCountsResponse.of(
                    propertyMapper.selectDistrictCounts(PropertySearchCondition.ofFilter(filter)),
                    OffsetDateTime.now(SEOUL));
            districtCountCacheStore.save(filter, response);
            return response;
        });
    }

    /** 좌표 조건이 있으면 마커, 없으면 목록. 반환 형태가 달라 호출자가 그대로 봉투에 담는다. */
    public Object search(PropertySearchRequest request) {
        boolean anyBox = Stream.of(request.minLat(), request.maxLat(), request.minLng(), request.maxLng())
                .anyMatch(v -> v != null);
        boolean fullBox = Stream.of(request.minLat(), request.maxLat(), request.minLng(), request.maxLng())
                .allMatch(v -> v != null);
        boolean anyRadius = Stream.of(request.lat(), request.lng(), request.radiusKm())
                .anyMatch(v -> v != null);
        boolean fullRadius = Stream.of(request.lat(), request.lng(), request.radiusKm())
                .allMatch(v -> v != null);

        if ((anyBox && !fullBox) || (anyRadius && !fullRadius) || (fullBox && fullRadius)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, anyBox ? "minLat" : "lat");
        }
        if (fullBox) {
            return searchMarkersInBox(request);
        }
        if (fullRadius) {
            return searchMarkersInRadius(request);
        }
        return searchList(request);
    }

    public PropertyDetailResponse getDetail(Long propertyId, Long userId) {
        PropertyDetailRow row = propertyMapper.selectDetail(new PropertyDetailCondition(propertyId, userId));
        if (row == null) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        return PropertyDetailResponse.from(row);
    }

    private PropertyMarkersResponse searchMarkersInBox(PropertySearchRequest request) {
        BoundingBox box = new BoundingBox(
                request.minLat(), request.maxLat(), request.minLng(), request.maxLng());
        return PropertyMarkersResponse.of(
                propertyMapper.selectMarkers(PropertySearchCondition.ofMarkers(request.toFilter(), box)));
    }

    /** 바운딩 박스로 1차 조회 → Haversine 으로 반경 밖 제거 → 거리순. 명세 1.3. */
    private PropertyMarkersResponse searchMarkersInRadius(PropertySearchRequest request) {
        double lat = request.lat();
        double lng = request.lng();
        double radiusKm = request.radiusKm();
        BoundingBox box = GeoDistanceCalculator.boundingBox(lat, lng, radiusKm);
        List<PropertyMarkerResponse> candidates =
                propertyMapper.selectMarkers(PropertySearchCondition.ofMarkers(request.toFilter(), box));

        record Ranked(PropertyMarkerResponse marker, double distanceKm) {
        }
        List<PropertyMarkerResponse> items = candidates.stream()
                .filter(m -> m.latitude() != null && m.longitude() != null)
                .map(m -> new Ranked(m, GeoDistanceCalculator.haversineKm(
                        lat, lng, m.latitude().doubleValue(), m.longitude().doubleValue())))
                .filter(r -> r.distanceKm() <= radiusKm)
                .sorted(Comparator.comparingDouble(Ranked::distanceKm)
                        .thenComparing(r -> r.marker().propertyId()))
                .map(Ranked::marker)
                .toList();
        return PropertyMarkersResponse.of(items);
    }

    private CursorPage<PropertyListResponse> searchList(PropertySearchRequest request) {
        PropertySortKey sortKey = DEFAULT_SORT_KEY;
        boolean ascending = false;
        if (request.sort() != null && !request.sort().isBlank()) {
            String[] parts = request.sort().split(",", -1);
            if (parts.length > 2) {
                throw invalidSort();
            }
            sortKey = PropertySortKey.fromParamName(parts[0].strip()).orElseThrow(this::invalidSort);
            if (parts.length == 2) {
                String direction = parts[1].strip();
                if (direction.equalsIgnoreCase("asc")) {
                    ascending = true;
                } else if (!direction.equalsIgnoreCase("desc")) {
                    throw invalidSort();
                }
            }
        }
        BigDecimal nullDebtRatio = ascending ? NULL_DEBT_RATIO_ASC : NULL_DEBT_RATIO_DESC;
        // 커서를 만든 정렬과 지금 요청의 정렬이 다르면 정렬 값을 다른 기준으로 읽게 된다 — 거부한다.
        String sortSignature = sortKey.name() + (ascending ? ",asc" : ",desc");

        Long lastId = null;
        Long lastDeposit = null;
        BigDecimal lastDebtRatio = null;
        LocalDateTime lastRegisteredAt = null;
        if (request.cursor() != null && !request.cursor().isBlank()) {
            CursorCodec.Cursor cursor = CursorCodec.decode(request.cursor());
            if (!sortSignature.equals(cursor.s())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
            }
            lastId = cursor.id();
            try {
                switch (sortKey) {
                    case DEPOSIT -> lastDeposit = Long.valueOf(cursor.v());
                    case DEBT_RATIO -> lastDebtRatio = new BigDecimal(cursor.v());
                    case REGISTERED_AT -> lastRegisteredAt = LocalDateTime.parse(cursor.v());
                }
            } catch (NullPointerException | NumberFormatException | DateTimeException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
            }
        }

        int size = request.size();
        List<PropertyListResponse> rows = propertyMapper.selectList(PropertySearchCondition.ofList(
                request.toFilter(), sortKey, ascending, nullDebtRatio,
                lastDeposit, lastDebtRatio, lastRegisteredAt, lastId, size + 1));

        boolean hasNext = rows.size() > size;
        List<PropertyListResponse> items = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = null;
        if (hasNext) {
            PropertyListResponse last = items.get(items.size() - 1);
            nextCursor = CursorCodec.encode(sortSignature, sortValue(last, sortKey, nullDebtRatio), last.propertyId());
        }
        return new CursorPage<>(List.copyOf(items), nextCursor, hasNext);
    }

    /** 커서에 담을 정렬 값. SQL 정렬 식과 같은 값이어야 한다 — 전세가율은 대체값, 등록일은 서울 벽시계 시각. */
    private static String sortValue(PropertyListResponse item, PropertySortKey sortKey, BigDecimal nullDebtRatio) {
        return switch (sortKey) {
            case DEPOSIT -> String.valueOf(item.deposit());
            case DEBT_RATIO -> (item.debtRatio() == null ? nullDebtRatio : item.debtRatio()).toPlainString();
            case REGISTERED_AT -> item.registeredAt().atZoneSameInstant(SEOUL).toLocalDateTime().toString();
        };
    }

    private BusinessException invalidSort() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "sort");
    }
}
