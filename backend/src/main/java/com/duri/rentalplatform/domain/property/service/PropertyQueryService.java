package com.duri.rentalplatform.domain.property.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.CursorCodec;
import com.duri.rentalplatform.common.CursorPage;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.datasource.ReplicaRead;
import com.duri.rentalplatform.domain.property.cache.DistrictCountCache;
import com.duri.rentalplatform.domain.property.cache.MapClusterCache;
import com.duri.rentalplatform.domain.property.calculator.GeoDistanceCalculator;
import com.duri.rentalplatform.domain.property.dto.condition.PropertyDetailCondition;
import com.duri.rentalplatform.domain.property.dto.condition.PropertyIdsCondition;
import com.duri.rentalplatform.domain.property.dto.condition.PropertySearchCondition;
import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.request.PropertyMapClustersRequest;
import com.duri.rentalplatform.domain.property.dto.request.PropertySearchRequest;
import com.duri.rentalplatform.domain.property.dto.response.DistrictCountsResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyDetailResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyListResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMapClustersResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkerResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkersResponse;
import com.duri.rentalplatform.domain.property.enums.PropertySortKey;
import com.duri.rentalplatform.domain.property.mapper.PropertyMapper;
import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import com.duri.rentalplatform.domain.property.vo.MapClusterCellRow;
import com.duri.rentalplatform.domain.property.vo.MarkerCandidateRow;
import com.duri.rentalplatform.domain.property.vo.PropertyDetailRow;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매물 조회. API 명세서(매물) 1.4 ~ 1.7 · 1.12. */
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

    /** 지도 묶음의 격자 — 표시 영역을 가로 · 세로 이 수만큼 나눈다. API 명세서(매물) 1.12. */
    static final int GRID_DIVISIONS = 12;

    /** 지도 묶음 임계 — 영역의 매물이 이 수 이하면 묶지 않고 전부 마커로 보낸다. API 명세서(매물) 1.12. */
    static final int CLUSTER_THRESHOLD = 40;

    /**
     * 반경 조회의 상한 — 가까운 순으로 이 수만 돌려준다. API 명세서(매물) 1.3. 지도 화면이 한 번에 그리는 최대 표시 수(격자 칸
     * 수 — 칸마다 묶음 하나나 마커 하나)와 같다. 상한 전에는 1 km 반경 한 번이 후보 1,842행 · 응답 약 590 KB 였다(#390).
     */
    static final int RADIUS_MARKER_LIMIT = GRID_DIVISIONS * GRID_DIVISIONS;

    private final PropertyMapper propertyMapper;
    private final DistrictCountCache districtCountCache;
    private final MapClusterCache mapClusterCache;

    /**
     * 자치구 집계. 같은 필터 조합은 캐시(슬롯 로컬 → Redis)에서 돌려준다 — {@link DistrictCountCache}. 둘 다 빗나가면 이
     * 스레드에서 집계한다. 읽기 분산이 켜지면 읽기용 풀에서 읽는다(#343).
     */
    @ReplicaRead
    public DistrictCountsResponse getDistrictCounts(DistrictCountRequest filter) {
        return districtCountCache.getOrLoad(filter, () -> DistrictCountsResponse.of(
                propertyMapper.selectDistrictCounts(PropertySearchCondition.ofFilter(filter)),
                OffsetDateTime.now(SEOUL)));
    }

    /**
     * 반경 조건이 있으면 마커, 없으면 목록. 반환 형태가 달라 호출자가 그대로 봉투에 담는다.
     * 표시 영역 좌표는 받지 않는다 — 하나라도 오면 INVALID_REQUEST. 명세 1.3. 읽기 분산이 켜지면 읽기용 풀에서 읽는다(#343).
     */
    @ReplicaRead
    public Object search(PropertySearchRequest request) {
        if (request.minLat() != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "minLat");
        }
        if (request.maxLat() != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "maxLat");
        }
        if (request.minLng() != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "minLng");
        }
        if (request.maxLng() != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "maxLng");
        }
        boolean anyRadius = Stream.of(request.lat(), request.lng(), request.radiusKm())
                .anyMatch(v -> v != null);
        boolean fullRadius = Stream.of(request.lat(), request.lng(), request.radiusKm())
                .allMatch(v -> v != null);

        if (anyRadius && !fullRadius) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "lat");
        }
        if (fullRadius) {
            return searchMarkersInRadius(request);
        }
        return searchList(request);
    }

    /**
     * 지도 묶음. 격자 칸 집계를 먼저 하고, 합계가 임계 이하면 영역 전량을 마커로, 넘으면 두 건 이상인 칸은
     * 묶음 · 한 건뿐인 칸은 마커로 돌려준다. API 명세서(매물) 1.12. 읽기 분산이 켜지면 읽기용 풀에서 읽는다(#343).
     *
     * <p>같은 필터 · 표시 영역 · 격자는 슬롯 로컬 캐시에서 돌려준다 — {@link MapClusterCache}. 빗나가면 이 스레드에서 조회한다.
     * 검증은 캐시보다 먼저 한다 — 잘못된 영역은 키를 만들지 않는다.
     */
    @ReplicaRead
    public PropertyMapClustersResponse getMapClusters(PropertyMapClustersRequest request) {
        BoundingBox box = validBox(request);
        DistrictCountRequest filter = request.toFilter();
        return mapClusterCache.getOrLoad(filter, box, GRID_DIVISIONS, () -> loadMapClusters(filter, box));
    }

    private PropertyMapClustersResponse loadMapClusters(DistrictCountRequest filter, BoundingBox box) {
        double cellLat = (box.maxLat() - box.minLat()) / GRID_DIVISIONS;
        double cellLng = (box.maxLng() - box.minLng()) / GRID_DIVISIONS;

        List<MapClusterCellRow> cells = propertyMapper.selectClusterCells(
                PropertySearchCondition.ofClusters(filter, box, cellLat, cellLng, GRID_DIVISIONS - 1));
        long total = cells.stream().mapToLong(MapClusterCellRow::count).sum();

        if (total <= CLUSTER_THRESHOLD) {
            List<PropertyMarkerResponse> markers = total == 0
                    ? List.of()
                    : propertyMapper.selectMarkers(PropertySearchCondition.ofMarkers(filter, box));
            return PropertyMapClustersResponse.unclustered(total, markers);
        }

        List<PropertyMapClustersResponse.Cluster> clusters = cells.stream()
                .filter(cell -> cell.count() > 1)
                .map(cell -> PropertyMapClustersResponse.Cluster.of(
                        cell, box.minLat(), box.minLng(), cellLat, cellLng))
                .toList();
        List<Long> singleIds = cells.stream()
                .filter(cell -> cell.count() == 1)
                .map(MapClusterCellRow::representativeId)
                .toList();
        List<PropertyMarkerResponse> markers = singleIds.isEmpty()
                ? List.of()
                : propertyMapper.selectMarkersByIds(new PropertyIdsCondition(singleIds));
        return PropertyMapClustersResponse.clustered(total, clusters, markers);
    }

    /** 표시 영역 네 값이 모두 있고 {@code min ≤ max} 여야 한다. 아니면 INVALID_REQUEST. */
    private static BoundingBox validBox(PropertyMapClustersRequest request) {
        if (request.minLat() == null || request.maxLat() == null || request.minLat() > request.maxLat()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "minLat");
        }
        if (request.minLng() == null || request.maxLng() == null || request.minLng() > request.maxLng()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "minLng");
        }
        return new BoundingBox(request.minLat(), request.maxLat(), request.minLng(), request.maxLng());
    }

    /**
     * 매물 상세. {@link ReplicaRead} 를 붙이지 않는다 — 응답에 로그인 사용자의 관심 여부가 들어가는데, 관심 등록 · 해제 직후
     * 다시 여는 화면이라 쓰기 직후 읽기다. standby 는 비동기 복제라 방금 커밋한 관심이 아직 없을 수 있어 기본 풀에서 읽는다(#343).
     */
    public PropertyDetailResponse getDetail(Long propertyId, Long userId) {
        PropertyDetailRow row = propertyMapper.selectDetail(new PropertyDetailCondition(propertyId, userId));
        if (row == null) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        return PropertyDetailResponse.from(row);
    }

    /**
     * 바운딩 박스로 후보(식별자 · 좌표) 조회 → Haversine 으로 반경 밖 제거 → 거리순(같으면 식별자 순) → 상한 건수로 자름 → 남은
     * 식별자만 마커 컬럼 조회 → 거리순으로 다시 늘어놓기. 명세 1.3.
     *
     * <p>마커 컬럼(선순위 채무 여부의 행마다 하위 질의 · 코드 · 판정 조인)은 잘린 뒤의 행에만 읽는다 — 후보 전부에 읽으면 반경
     * 1 km 에서 1,842행마다 근저당 하위 질의가 돌았다(#390 E01). 후보 조회와 마커 조회 사이에 매물이 바뀌어 마커 조회에서 빠진
     * 식별자는 응답에서도 빠진다 — 읽기 전용 트랜잭션의 기본 격리 수준이라 두 조회가 각자의 스냅숏을 본다.
     */
    private PropertyMarkersResponse searchMarkersInRadius(PropertySearchRequest request) {
        double lat = request.lat();
        double lng = request.lng();
        double radiusKm = request.radiusKm();
        BoundingBox box = GeoDistanceCalculator.boundingBox(lat, lng, radiusKm);
        List<MarkerCandidateRow> candidates =
                propertyMapper.selectMarkerCandidates(PropertySearchCondition.ofMarkers(request.toFilter(), box));

        record Ranked(Long propertyId, double distanceKm) {
        }
        List<Long> inRadius = candidates.stream()
                .filter(c -> c.latitude() != null && c.longitude() != null)
                .map(c -> new Ranked(c.propertyId(), GeoDistanceCalculator.haversineKm(
                        lat, lng, c.latitude().doubleValue(), c.longitude().doubleValue())))
                .filter(r -> r.distanceKm() <= radiusKm)
                .sorted(Comparator.comparingDouble(Ranked::distanceKm).thenComparing(Ranked::propertyId))
                .map(Ranked::propertyId)
                .toList();
        boolean truncated = inRadius.size() > RADIUS_MARKER_LIMIT;
        List<Long> ids = truncated ? inRadius.subList(0, RADIUS_MARKER_LIMIT) : inRadius;
        if (ids.isEmpty()) {
            return PropertyMarkersResponse.of(List.of(), false);
        }

        Map<Long, PropertyMarkerResponse> byId = propertyMapper.selectMarkersByIds(new PropertyIdsCondition(ids))
                .stream()
                .collect(Collectors.toMap(PropertyMarkerResponse::propertyId, Function.identity()));
        List<PropertyMarkerResponse> items = ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        return PropertyMarkersResponse.of(items, truncated);
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
        PropertySearchCondition condition = PropertySearchCondition.ofList(
                request.toFilter(), sortKey, ascending, nullDebtRatio,
                lastDeposit, lastDebtRatio, lastRegisteredAt, lastId, size + 1);
        List<PropertyListResponse> rows = usesLeaseRatioIndex(condition)
                ? selectListByLeaseRatio(condition)
                : propertyMapper.selectList(condition);

        boolean hasNext = rows.size() > size;
        List<PropertyListResponse> items = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = null;
        if (hasNext) {
            PropertyListResponse last = items.get(items.size() - 1);
            nextCursor = CursorCodec.encode(sortSignature, sortValue(last, sortKey, nullDebtRatio), last.propertyId());
        }
        return new CursorPage<>(List.copyOf(items), nextCursor, hasNext);
    }

    /**
     * 매물 전세가율 열의 부분 인덱스(V24 — V20 판정 표 인덱스의 역할을 옮김)에서 출발할 목록인가 — 자치구가 없고, 전세가율순이고,
     * 매물 조건 필터(계약 · 유형 · 보증금 · 월세 · 면적 · 좌표)가 모두 없을 때만. 매물 조건 필터가 있으면 걸러지는 만큼 인덱스를 더
     * 읽어 오름에서 93.9 → 313.3ms 로 나빠졌다(#347 운영 측정, V20 인덱스). 등급 필터는 인덱스에 담긴 열이라 허용한다. 자치구가
     * 있으면 selectList 가 자치구 인덱스로 좁힌다.
     */
    private static boolean usesLeaseRatioIndex(PropertySearchCondition c) {
        return c.sortKey() == PropertySortKey.DEBT_RATIO
                && (c.district() == null || c.district().isEmpty())
                && c.contractType() == null && c.propertyType() == null
                && c.depositMin() == null && c.depositMax() == null && c.monthlyRentMax() == null
                && c.areaMin() == null && c.areaMax() == null
                && c.minLat() == null && c.maxLat() == null && c.minLng() == null && c.maxLng() == null;
    }

    /**
     * 자치구 없는 전세가율순 목록을 앞부분 · 끝부분으로 나눠 읽는다. 결과 · 순서 · 커서는 selectList 한 번과 같다.
     *
     * <p>selectList 는 COALESCE(전세가율, 대체값) 으로 정렬해 판정이 없는 매물을 대체값 자리(방향과 무관하게 맨 뒤)에 둔다. 이
     * 순서를 대체값 경계에서 둘로 자른다.
     * <ul>
     *   <li>앞부분 — 전세가율이 대체값보다 앞(오름 &lt; 1000 · 내림 &gt; -1000). 매물 전세가율 인덱스에서 출발하는
     *       selectListByLeaseRatioIndex 가 읽는다. 정렬 값이 전세가율 그대로라 selectList 의 정렬 값과 같다.</li>
     *   <li>끝부분 — 정렬 값이 대체값이거나 그 너머(판정 없음, 전세가율이 경계 바깥). selectList 가 읽는다. lease_ratio 가
     *       NUMERIC(5,2) NOT NULL 이라 지금은 판정 없는 매물뿐이고, 등급 필터가 있으면 비어 있다.</li>
     * </ul>
     * 두 구간의 정렬 값은 겹치지 않아(앞부분 &lt; 대체값 ≤ 끝부분, 내림은 반대) 이어 붙이면 중복 없이 정렬이 유지된다.
     *
     * <ul>
     *   <li>커서가 끝부분(오름 ≥ 대체값 · 내림 ≤ 대체값)이면 앞부분은 이미 다 읽었다 — 받은 조건 그대로 selectList.</li>
     *   <li>아니면 앞부분을 limit(요청 크기 + 1) 건 읽고, 모자라면(앞부분이 끝남) 끝부분 처음부터 모자란 수만큼 selectList 로
     *       이어 읽는다. 끝부분의 처음은 커서 (대체값, 식별자 하한) 으로 연다 — 키셋 조건 「정렬 값 &gt; 대체값 또는
     *       (= 대체값 이고 식별자 &gt; 하한)」이 정렬 값이 대체값 이상인 행 전부가 된다. 내림은 (대체값, 식별자 상한) 과 &lt;.</li>
     * </ul>
     * 합친 건수는 limit 을 넘지 않으므로 hasNext 판정(limit 초과 여부)과 다음 커서(마지막 행의 정렬 값 · 식별자)는 호출자가
     * selectList 결과와 똑같이 만든다.
     *
     * <p>끝부분 조회는 selectList 의 느린 계획(COALESCE 정렬 · 전체 읽기)이다. 앞부분(판정이 있는 매물 전부)을 다 넘긴 마지막 페이지에서만
     * 돈다. 두 조회는 각자의 스냅숏을 본다(읽기 전용 트랜잭션의 기본 격리 수준) — 사이에 판정이 바뀐 매물은 한쪽에서 빠지거나 두
     * 번 보일 수 있다. 커서를 넘기는 페이지 사이에서도 이미 같은 일이 생기므로 새 경우가 아니다.
     */
    private List<PropertyListResponse> selectListByLeaseRatio(PropertySearchCondition c) {
        boolean ascending = c.ascending();
        BigDecimal boundary = c.nullDebtRatio();
        if (c.lastId() != null) {
            int cmp = c.lastDebtRatio().compareTo(boundary);
            if (ascending ? cmp >= 0 : cmp <= 0) {
                return propertyMapper.selectList(c);
            }
        }
        List<PropertyListResponse> head = propertyMapper.selectListByLeaseRatioIndex(c);
        int remaining = c.limit() - head.size();
        if (remaining <= 0) {
            return head;
        }
        // 식별자는 BIGINT 라 이 하한 · 상한과 같은 매물은 없다 — 끝부분 첫 행을 빠뜨리지 않는다.
        long idBound = ascending ? Long.MIN_VALUE : Long.MAX_VALUE;
        List<PropertyListResponse> tail = propertyMapper.selectList(new PropertySearchCondition(
                c.district(), c.contractType(), c.depositMin(), c.depositMax(), c.monthlyRentMax(),
                c.propertyType(), c.riskGrades(), c.areaMin(), c.areaMax(),
                c.minLat(), c.maxLat(), c.minLng(), c.maxLng(),
                c.sortKey(), ascending, boundary, c.lastDeposit(), boundary, c.lastRegisteredAt(),
                idBound, remaining, c.cellLat(), c.cellLng(), c.maxCellIndex()));
        if (tail.isEmpty()) {
            return head;
        }
        return Stream.concat(head.stream(), tail.stream()).toList();
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
