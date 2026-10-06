package com.duri.rentalplatform.domain.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.CursorCodec;
import com.duri.rentalplatform.common.CursorPage;
import com.duri.rentalplatform.common.ErrorCode;
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
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.mapper.PropertyMapper;
import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import com.duri.rentalplatform.domain.property.vo.MapClusterCellRow;
import com.duri.rentalplatform.domain.property.vo.MarkerCandidateRow;
import com.duri.rentalplatform.domain.property.vo.PropertyDetailRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link PropertyQueryService} 검증. 매퍼와 캐시 보관소가 모두 인터페이스/빈이라 Mockito 스텁으로
 * 대체한다 — 데이터베이스도 Redis도 필요 없다.
 */
class PropertyQueryServiceTest {

    private PropertyMapper propertyMapper;
    private DistrictCountCache districtCountCache;
    private MapClusterCache mapClusterCache;
    private PropertyQueryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        propertyMapper = mock(PropertyMapper.class);
        districtCountCache = mock(DistrictCountCache.class);
        // 지도 묶음 캐시는 기본으로 빗나간다 — 받은 읽기를 그대로 부른다. 적중은 캐시 테스트(MapClusterCacheTest)가 본다.
        mapClusterCache = mock(MapClusterCache.class);
        when(mapClusterCache.getOrLoad(any(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> ((Supplier<PropertyMapClustersResponse>) invocation.getArgument(4)).get());
        service = new PropertyQueryService(propertyMapper, districtCountCache, mapClusterCache);
    }

    private static PropertySearchRequest listRequest(Integer size) {
        return new PropertySearchRequest(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, size);
    }

    private static PropertySearchRequest boxRequest(
            Double minLat, Double maxLat, Double minLng, Double maxLng) {
        return new PropertySearchRequest(null, null, null, null, null, null, null, null, null,
                minLat, maxLat, minLng, maxLng, null, null, null, null, null, null);
    }

    private static PropertySearchRequest radiusRequest(Double lat, Double lng, Double radiusKm) {
        return new PropertySearchRequest(null, null, null, null, null, null, null, null, null,
                null, null, null, null, lat, lng, radiusKm, null, null, null);
    }

    private static PropertyListResponse listRow(long propertyId, OffsetDateTime registeredAt) {
        return new PropertyListResponse(propertyId, "강남구", "역삼동 100-1", PropertyType.APARTMENT,
                ContractType.DEPOSIT_ONLY, 300_000_000L, 0L, new BigDecimal("59.90"), 3,
                null, null, registeredAt);
    }

    private static PropertyMarkerResponse marker(long propertyId, String lat, String lng) {
        return new PropertyMarkerResponse(propertyId, new BigDecimal(lat), new BigDecimal(lng),
                300_000_000L, null, ContractType.DEPOSIT_ONLY, 0L, "강남구", null, null);
    }

    @Test
    @DisplayName("좌표 조건이 없으면 목록을 size+1건 조회해 hasNext=true·nextCursor를 채운다")
    void searchListHasNextWhenMoreRowsThanSize() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        List<PropertyListResponse> rows = List.of(
                listRow(1L, base), listRow(2L, base.minusMinutes(1)), listRow(3L, base.minusMinutes(2)));
        when(propertyMapper.selectList(any())).thenReturn(rows);

        Object result = service.search(listRequest(2));

        @SuppressWarnings("unchecked")
        CursorPage<PropertyListResponse> page = (CursorPage<PropertyListResponse>) result;
        assertThat(page.items()).hasSize(2);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isNotNull();

        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper).selectList(captor.capture());
        assertThat(captor.getValue().limit()).isEqualTo(3);
    }

    @Test
    @DisplayName("다른 정렬로 받은 커서를 넣으면 틀린 페이지 대신 INVALID_REQUEST(cursor)다")
    void rejectsCursorIssuedForAnotherSort() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        when(propertyMapper.selectList(any())).thenReturn(List.of(
                listRow(1L, base), listRow(2L, base.minusMinutes(1)), listRow(3L, base.minusMinutes(2))));
        CursorPage<?> depositPage = (CursorPage<?>) service.search(new PropertySearchRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "deposit,asc", null, 2));

        for (String otherSort : List.of("debtRatio,asc", "deposit,desc")) {
            PropertySearchRequest reused = new PropertySearchRequest(
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, otherSort, depositPage.nextCursor(), 2);
            assertThatThrownBy(() -> service.search(reused))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_REQUEST));
        }
    }

    @Test
    @DisplayName("좌표 조건이 없고 조회 결과가 size건이면 hasNext=false·nextCursor=null이다")
    void searchListNoNextWhenRowsEqualSize() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        List<PropertyListResponse> rows = List.of(listRow(1L, base), listRow(2L, base.minusMinutes(1)));
        when(propertyMapper.selectList(any())).thenReturn(rows);

        Object result = service.search(listRequest(2));

        @SuppressWarnings("unchecked")
        CursorPage<PropertyListResponse> page = (CursorPage<PropertyListResponse>) result;
        assertThat(page.items()).hasSize(2);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    private static MarkerCandidateRow candidate(long propertyId, String lat, String lng) {
        return new MarkerCandidateRow(propertyId, new BigDecimal(lat), new BigDecimal(lng));
    }

    /** 식별자 목록 그대로 마커를 돌려주는 매퍼 스텁 — 매퍼처럼 식별자 순으로 돌려준다. */
    private void stubMarkersByIds() {
        when(propertyMapper.selectMarkersByIds(any())).thenAnswer(invocation -> {
            PropertyIdsCondition condition = invocation.getArgument(0);
            return condition.propertyIds().stream().sorted().map(id -> marker(id, "37.5", "127.0")).toList();
        });
    }

    @Test
    @DisplayName("반경 조건은 바운딩 박스로 후보를 조회하고 반경 밖을 제거해 거리순으로, 남은 식별자만 마커로 읽는다")
    void searchMarkersInRadiusFiltersAndSortsByDistance() {
        double lat = 37.5;
        double lng = 127.0;
        double radiusKm = 1.0;
        // near: 중심에서 약 0.03km. far: 중심에서 약 0.08km. outside: 바운딩 박스 모서리 근처, 반경(1km) 밖.
        // 매퍼가 거리순이 아닌 순서로 돌려줘도 서비스가 거리순으로 다시 정렬해야 한다.
        when(propertyMapper.selectMarkerCandidates(any())).thenReturn(List.of(
                candidate(2L, "37.5007", "127.0000"), candidate(3L, "37.5100", "127.0100"),
                candidate(1L, "37.5003", "127.0000")));
        stubMarkersByIds();

        Object result = service.search(radiusRequest(lat, lng, radiusKm));

        PropertyMarkersResponse response = (PropertyMarkersResponse) result;
        // 마커 조회는 식별자 순(1, 2)으로 돌려주지만 응답은 거리순이다 — 여기서는 우연히 같아 아래 테스트가 다른 순서를 본다.
        assertThat(response.items()).extracting(PropertyMarkerResponse::propertyId).containsExactly(1L, 2L);
        assertThat(response.count()).isEqualTo(2);
        assertThat(response.truncated()).isFalse();

        BoundingBox expectedBox = GeoDistanceCalculator.boundingBox(lat, lng, radiusKm);
        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper).selectMarkerCandidates(captor.capture());
        assertThat(captor.getValue().minLat()).isEqualTo(expectedBox.minLat());
        assertThat(captor.getValue().maxLat()).isEqualTo(expectedBox.maxLat());
        assertThat(captor.getValue().minLng()).isEqualTo(expectedBox.minLng());
        assertThat(captor.getValue().maxLng()).isEqualTo(expectedBox.maxLng());
        ArgumentCaptor<PropertyIdsCondition> ids = ArgumentCaptor.forClass(PropertyIdsCondition.class);
        verify(propertyMapper).selectMarkersByIds(ids.capture());
        assertThat(ids.getValue().propertyIds()).containsExactly(1L, 2L);
        verify(propertyMapper, never()).selectMarkers(any());
    }

    @Test
    @DisplayName("반경: 마커 조회가 식별자 순으로 돌려줘도 응답은 거리순이고, 같은 거리는 식별자 순이다")
    void searchMarkersInRadiusKeepsDistanceOrderOverIdOrder() {
        // 30(가까움) · 10(멀음) · 20 과 21(같은 좌표 — 같은 거리)
        when(propertyMapper.selectMarkerCandidates(any())).thenReturn(List.of(
                candidate(10L, "37.5050", "127.0000"), candidate(21L, "37.5020", "127.0000"),
                candidate(30L, "37.5001", "127.0000"), candidate(20L, "37.5020", "127.0000")));
        stubMarkersByIds();

        PropertyMarkersResponse response = (PropertyMarkersResponse) service.search(radiusRequest(37.5, 127.0, 1.0));

        assertThat(response.items()).extracting(PropertyMarkerResponse::propertyId)
                .containsExactly(30L, 20L, 21L, 10L);
    }

    @Test
    @DisplayName("반경: 반경 안이 상한(144)보다 많으면 가까운 144건만 마커로 읽고 truncated=true")
    void searchMarkersInRadiusTruncatesAtLimit() {
        int limit = PropertyQueryService.RADIUS_MARKER_LIMIT;
        // 식별자가 클수록 멀다(위도 0.00001 ≈ 1.1 m 씩). 섞어서 돌려준다.
        List<MarkerCandidateRow> candidates = new java.util.ArrayList<>();
        for (long id = limit + 5; id >= 1; id--) {
            candidates.add(candidate(id, new BigDecimal("37.5").add(new BigDecimal("0.00001").multiply(BigDecimal.valueOf(id)))
                    .toPlainString(), "127.0"));
        }
        java.util.Collections.shuffle(candidates, new java.util.Random(7));
        when(propertyMapper.selectMarkerCandidates(any())).thenReturn(candidates);
        stubMarkersByIds();

        PropertyMarkersResponse response = (PropertyMarkersResponse) service.search(radiusRequest(37.5, 127.0, 1.0));

        assertThat(limit).isEqualTo(144);
        assertThat(response.truncated()).isTrue();
        assertThat(response.count()).isEqualTo(limit);
        assertThat(response.items()).extracting(PropertyMarkerResponse::propertyId)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, limit).boxed().toList());
        ArgumentCaptor<PropertyIdsCondition> ids = ArgumentCaptor.forClass(PropertyIdsCondition.class);
        verify(propertyMapper).selectMarkersByIds(ids.capture());
        assertThat(ids.getValue().propertyIds()).hasSize(limit);
    }

    @Test
    @DisplayName("반경: 반경 안이 상한과 같으면 자르지 않는다 — truncated=false")
    void searchMarkersInRadiusAtLimitIsNotTruncated() {
        int limit = PropertyQueryService.RADIUS_MARKER_LIMIT;
        List<MarkerCandidateRow> candidates = java.util.stream.LongStream.rangeClosed(1, limit)
                .mapToObj(id -> candidate(id, "37.5001", "127.0")).toList();
        when(propertyMapper.selectMarkerCandidates(any())).thenReturn(candidates);
        stubMarkersByIds();

        PropertyMarkersResponse response = (PropertyMarkersResponse) service.search(radiusRequest(37.5, 127.0, 1.0));

        assertThat(response.truncated()).isFalse();
        assertThat(response.count()).isEqualTo(limit);
    }

    @Test
    @DisplayName("반경: 반경 안에 매물이 없으면 마커 조회를 하지 않고 빈 목록이다")
    void searchMarkersInRadiusEmptySkipsMarkerQuery() {
        when(propertyMapper.selectMarkerCandidates(any())).thenReturn(List.of(candidate(3L, "37.5100", "127.0100")));

        PropertyMarkersResponse response = (PropertyMarkersResponse) service.search(radiusRequest(37.5, 127.0, 1.0));

        assertThat(response.items()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.truncated()).isFalse();
        verify(propertyMapper, never()).selectMarkersByIds(any());
    }

    @Test
    @DisplayName("표시 영역 네 값이 모두 있어도 INVALID_REQUEST(minLat)다 — 영역 조회는 1.12 묶음 조회의 몫")
    void rejectsFullBox() {
        assertInvalidRequest(boxRequest(37.0, 38.0, 126.0, 128.0), "minLat");
    }

    @Test
    @DisplayName("표시 영역 값이 하나만 와도 그 파라미터 이름으로 INVALID_REQUEST다")
    void rejectsSingleBoxParam() {
        assertInvalidRequest(boxRequest(null, null, null, 127.0), "maxLng");
    }

    @Test
    @DisplayName("중심·반경 세 값 중 일부만 있으면 INVALID_REQUEST(lat)다")
    void rejectsPartialRadius() {
        assertInvalidRequest(radiusRequest(37.5, null, null), "lat");
    }

    @Test
    @DisplayName("반경 조건과 함께 표시 영역이 와도 INVALID_REQUEST(minLat)다")
    void rejectsBoxEvenWithRadius() {
        PropertySearchRequest request = new PropertySearchRequest(null, null, null, null, null, null,
                null, null, null, 37.0, 38.0, 126.0, 128.0, 37.5, 127.0, 1.0, null, null, null);
        assertInvalidRequest(request, "minLat");
    }

    private void assertInvalidRequest(PropertySearchRequest request, String expectedField) {
        assertThatThrownBy(() -> service.search(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(be.getField()).isEqualTo(expectedField);
                });
        verify(propertyMapper, never()).selectMarkers(any());
        verify(propertyMapper, never()).selectMarkerCandidates(any());
        verify(propertyMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("상세 조회 결과가 없으면 PROPERTY_NOT_FOUND다")
    void detailNotFoundThrows() {
        when(propertyMapper.selectDetail(any(PropertyDetailCondition.class))).thenReturn(null);

        assertThatThrownBy(() -> service.getDetail(999L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PROPERTY_NOT_FOUND));
    }

    @Test
    @DisplayName("상세 조회 결과가 있으면 응답으로 변환해 반환한다")
    void detailFoundReturnsResponse() {
        PropertyDetailRow row = new PropertyDetailRow(1L, "강남구", "역삼동 100-1",
                new BigDecimal("37.5"), new BigDecimal("127.0"), PropertyType.APARTMENT,
                ContractType.DEPOSIT_ONLY, 300_000_000L, 0L, new BigDecimal("59.90"), 3,
                "임대인", 300_000_000L, null, null, null, null, null, null, null);
        when(propertyMapper.selectDetail(new PropertyDetailCondition(1L, 5L))).thenReturn(row);

        PropertyDetailResponse response = service.getDetail(1L, 5L);

        assertThat(response.propertyId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("자치구 집계는 캐시에 있으면 매퍼를 호출하지 않고 캐시 값을 그대로 반환한다")
    void districtCountsCacheHitSkipsMapper() {
        DistrictCountRequest filter = new DistrictCountRequest(
                null, null, null, null, null, null, null, null, null);
        DistrictCountsResponse cached = new DistrictCountsResponse(List.of(), 7L, OffsetDateTime.now());
        when(districtCountCache.getOrLoad(eq(filter), any())).thenReturn(cached);

        DistrictCountsResponse result = service.getDistrictCounts(filter);

        assertThat(result).isEqualTo(cached);
        verify(propertyMapper, never()).selectDistrictCounts(any());
    }

    @Test
    @DisplayName("자치구 집계는 캐시가 빗나가면 캐시가 부르는 적재 함수로 매퍼를 조회한다")
    void districtCountsCacheMissQueriesMapper() {
        DistrictCountRequest filter = new DistrictCountRequest(
                null, null, null, null, null, null, null, null, null);
        when(districtCountCache.getOrLoad(eq(filter), any())).thenAnswer(invocation -> {
            Supplier<DistrictCountsResponse> loader = invocation.getArgument(1);
            return loader.get();
        });
        when(propertyMapper.selectDistrictCounts(any())).thenReturn(List.of());

        DistrictCountsResponse result = service.getDistrictCounts(filter);

        assertThat(result.totalCount()).isZero();
        assertThat(result.aggregatedAt()).isNotNull();
        verify(propertyMapper).selectDistrictCounts(any());
    }

    // ---------- 지도 묶음 (명세 1.12) ----------

    private static PropertyMapClustersRequest clustersRequest(
            Double minLat, Double maxLat, Double minLng, Double maxLng) {
        return clustersRequest(minLat, maxLat, minLng, maxLng, null, null);
    }

    private static PropertyMapClustersRequest clustersRequest(
            Double minLat, Double maxLat, Double minLng, Double maxLng, Integer rows, Integer cols) {
        return new PropertyMapClustersRequest("강서구", null, null, null, null, null, null, null, null,
                minLat, maxLat, minLng, maxLng, rows, cols);
    }

    /** 명세 1.12 의 요청 예시 영역. */
    private static PropertyMapClustersRequest exampleClustersRequest() {
        return clustersRequest(37.52, 37.58, 126.81, 126.89);
    }

    private static MapClusterCellRow cell(int row, int col, long count, long representativeId) {
        return new MapClusterCellRow(row, col, count, new BigDecimal("37.5476"), new BigDecimal("126.8601"),
                count, 0L, 0L, 0L, representativeId);
    }

    @Test
    @DisplayName("지도 묶음: 합계가 임계(40) 이하면 묶지 않고 영역 전량을 마커로 조회한다")
    void mapClustersAtThresholdReturnsAllMarkers() {
        when(propertyMapper.selectClusterCells(any())).thenReturn(List.of(
                cell(0, 0, 39, 1L), cell(3, 3, 1, 2L)));
        List<PropertyMarkerResponse> all = List.of(marker(1L, "37.53", "126.82"));
        when(propertyMapper.selectMarkers(any())).thenReturn(all);

        PropertyMapClustersResponse result = service.getMapClusters(exampleClustersRequest());

        assertThat(result.total()).isEqualTo(PropertyQueryService.CLUSTER_THRESHOLD);
        assertThat(result.clustered()).isFalse();
        assertThat(result.clusters()).isEmpty();
        assertThat(result.markers()).isEqualTo(all);
        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper).selectMarkers(captor.capture());
        assertThat(captor.getValue().district()).isEqualTo("강서구");
        assertThat(captor.getValue().minLat()).isEqualTo(37.52);
        assertThat(captor.getValue().maxLng()).isEqualTo(126.89);
        verify(propertyMapper, never()).selectMarkersByIds(any());
    }

    @Test
    @DisplayName("지도 묶음: 행 · 열이 없으면 기본 12 — 칸 크기 = 영역 ÷ 12, 마지막 행 · 열 번호 11 을 넘긴다")
    void mapClustersPassesGridToMapper() {
        when(propertyMapper.selectClusterCells(any())).thenReturn(List.of());

        service.getMapClusters(exampleClustersRequest());

        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper).selectClusterCells(captor.capture());
        PropertySearchCondition condition = captor.getValue();
        assertThat(condition.cellLat()).isCloseTo((37.58 - 37.52) / 12, Offset.offset(1e-12));
        assertThat(condition.cellLng()).isCloseTo((126.89 - 126.81) / 12, Offset.offset(1e-12));
        assertThat(condition.maxRowIndex()).isEqualTo(PropertyMapClustersRequest.DEFAULT_GRID_DIVISIONS - 1);
        assertThat(condition.maxColIndex()).isEqualTo(PropertyMapClustersRequest.DEFAULT_GRID_DIVISIONS - 1);
        assertThat(condition.district()).isEqualTo("강서구");
    }

    @Test
    @DisplayName("지도 묶음: 행 ≠ 열이면 칸 높이 = 높이 ÷ 행, 칸 너비 = 폭 ÷ 열, 마지막 번호도 축별이다")
    void mapClustersUsesRowsAndColsPerAxis() {
        when(propertyMapper.selectClusterCells(any())).thenReturn(List.of());

        service.getMapClusters(clustersRequest(37.52, 37.58, 126.81, 126.89, 18, 15));

        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper).selectClusterCells(captor.capture());
        PropertySearchCondition condition = captor.getValue();
        assertThat(condition.cellLat()).isCloseTo((37.58 - 37.52) / 18, Offset.offset(1e-12));
        assertThat(condition.cellLng()).isCloseTo((126.89 - 126.81) / 15, Offset.offset(1e-12));
        assertThat(condition.maxRowIndex()).isEqualTo(17);
        assertThat(condition.maxColIndex()).isEqualTo(14);
    }

    @Test
    @DisplayName("지도 묶음: 행 · 열 중 하나만 오면 빠진 쪽만 기본 12 다")
    void mapClustersDefaultsMissingAxisOnly() {
        when(propertyMapper.selectClusterCells(any())).thenReturn(List.of());

        service.getMapClusters(clustersRequest(37.52, 37.58, 126.81, 126.89, null, 20));

        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper).selectClusterCells(captor.capture());
        PropertySearchCondition condition = captor.getValue();
        assertThat(condition.cellLat()).isCloseTo((37.58 - 37.52) / 12, Offset.offset(1e-12));
        assertThat(condition.cellLng()).isCloseTo((126.89 - 126.81) / 20, Offset.offset(1e-12));
        assertThat(condition.maxRowIndex()).isEqualTo(11);
        assertThat(condition.maxColIndex()).isEqualTo(19);
    }

    @Test
    @DisplayName("지도 묶음: 임계를 넘으면 두 건 이상인 칸은 묶음, 한 건 칸은 대표 식별자로 마커를 조회한다")
    void mapClustersOverThresholdSplitsClustersAndSingles() {
        when(propertyMapper.selectClusterCells(any())).thenReturn(List.of(
                new MapClusterCellRow(5, 7, 39L, new BigDecimal("37.5476"), new BigDecimal("126.8601"),
                        20L, 10L, 5L, 4L, 10L),
                cell(0, 0, 1, 99L),
                cell(11, 11, 1, 100L)));
        List<PropertyMarkerResponse> singles = List.of(marker(99L, "37.52", "126.81"),
                marker(100L, "37.58", "126.89"));
        when(propertyMapper.selectMarkersByIds(any())).thenReturn(singles);

        PropertyMapClustersResponse result = service.getMapClusters(exampleClustersRequest());

        assertThat(result.total()).isEqualTo(41L);
        assertThat(result.clustered()).isTrue();
        assertThat(result.markers()).isEqualTo(singles);
        assertThat(result.clusters()).hasSize(1);
        PropertyMapClustersResponse.Cluster cluster = result.clusters().get(0);
        assertThat(cluster.key()).isEqualTo("5:7");
        assertThat(cluster.count()).isEqualTo(39);
        assertThat(cluster.latitude()).isEqualByComparingTo("37.5476");
        assertThat(cluster.longitude()).isEqualByComparingTo("126.8601");
        assertThat(cluster.gradeCounts())
                .isEqualTo(new PropertyMapClustersResponse.GradeCounts(20, 10, 5, 4));
        // 명세 1.12 응답 예시의 칸 경계 — 37.52 + 5 × 0.005, 126.81 + 7 × (0.08 / 12)
        assertThat(cluster.minLat()).isEqualByComparingTo("37.545");
        assertThat(cluster.maxLat()).isEqualByComparingTo("37.55");
        assertThat(cluster.minLng()).isEqualByComparingTo("126.8566667");
        assertThat(cluster.maxLng()).isEqualByComparingTo("126.8633333");

        ArgumentCaptor<PropertyIdsCondition> captor = ArgumentCaptor.forClass(PropertyIdsCondition.class);
        verify(propertyMapper).selectMarkersByIds(captor.capture());
        assertThat(captor.getValue().propertyIds()).containsExactly(99L, 100L);
        verify(propertyMapper, never()).selectMarkers(any());
    }

    @Test
    @DisplayName("지도 묶음: 임계를 넘어도 한 건 칸이 없으면 식별자 마커 조회를 하지 않는다")
    void mapClustersWithoutSinglesSkipsIdQuery() {
        when(propertyMapper.selectClusterCells(any())).thenReturn(List.of(cell(0, 0, 20, 1L), cell(1, 1, 21, 2L)));

        PropertyMapClustersResponse result = service.getMapClusters(exampleClustersRequest());

        assertThat(result.clustered()).isTrue();
        assertThat(result.clusters()).extracting(PropertyMapClustersResponse.Cluster::key)
                .containsExactly("0:0", "1:1");
        assertThat(result.markers()).isEmpty();
        verify(propertyMapper, never()).selectMarkersByIds(any());
        verify(propertyMapper, never()).selectMarkers(any());
    }

    @Test
    @DisplayName("지도 묶음: 영역에 매물이 없으면 total 0 · 빈 목록이고 마커 조회를 하지 않는다")
    void mapClustersEmpty() {
        when(propertyMapper.selectClusterCells(any())).thenReturn(List.of());

        PropertyMapClustersResponse result = service.getMapClusters(exampleClustersRequest());

        assertThat(result.total()).isZero();
        assertThat(result.clustered()).isFalse();
        assertThat(result.clusters()).isEmpty();
        assertThat(result.markers()).isEmpty();
        verify(propertyMapper, never()).selectMarkers(any());
        verify(propertyMapper, never()).selectMarkersByIds(any());
    }

    @Test
    @DisplayName("지도 묶음: min 이 max 보다 크거나 영역 값이 빠지면 INVALID_REQUEST 이고 조회하지 않는다")
    void mapClustersRejectsInvalidBox() {
        List<PropertyMapClustersRequest> invalid = List.of(
                clustersRequest(37.58, 37.52, 126.81, 126.89),
                clustersRequest(37.52, 37.58, 126.89, 126.81),
                clustersRequest(null, 37.58, 126.81, 126.89),
                clustersRequest(37.52, 37.58, 126.81, null));
        for (PropertyMapClustersRequest request : invalid) {
            assertThatThrownBy(() -> service.getMapClusters(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        verify(propertyMapper, never()).selectClusterCells(any());
        verify(mapClusterCache, never()).getOrLoad(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("지도 묶음: 캐시에 필터 · 표시 영역 · 기본 행 · 열(12, 12)을 넘기고, 적중하면 DB 를 부르지 않는다")
    void mapClustersUsesCache() {
        PropertyMapClustersResponse cached = PropertyMapClustersResponse.unclustered(3, List.of());
        // when(...) 로 다시 스텁하면 setUp 의 응답(받은 읽기 실행)이 먼저 불린다 — doReturn 으로 덮는다.
        doReturn(cached).when(mapClusterCache).getOrLoad(any(), any(), anyInt(), anyInt(), any());

        PropertyMapClustersResponse result = service.getMapClusters(exampleClustersRequest());

        assertThat(result).isSameAs(cached);
        verify(mapClusterCache).getOrLoad(eq(exampleClustersRequest().toFilter()),
                eq(new BoundingBox(37.52, 37.58, 126.81, 126.89)), eq(PropertyMapClustersRequest.DEFAULT_GRID_DIVISIONS),
                eq(PropertyMapClustersRequest.DEFAULT_GRID_DIVISIONS), any());
        verify(propertyMapper, never()).selectClusterCells(any());
    }

    @Test
    @DisplayName("지도 묶음: 요청의 행 · 열을 캐시 키 인자로 그대로 넘긴다")
    void mapClustersPassesRowsAndColsToCache() {
        doReturn(PropertyMapClustersResponse.unclustered(0, List.of()))
                .when(mapClusterCache).getOrLoad(any(), any(), anyInt(), anyInt(), any());
        PropertyMapClustersRequest request = clustersRequest(37.52, 37.58, 126.81, 126.89, 18, 15);

        service.getMapClusters(request);

        verify(mapClusterCache).getOrLoad(eq(request.toFilter()),
                eq(new BoundingBox(37.52, 37.58, 126.81, 126.89)), eq(18), eq(15), any());
    }

    // ---------- 목록: 전세가율 인덱스 분기 · 이어 붙이기 (PROP-01 · #347) ----------
    // 좌표 네 값은 search() 가 앞에서 INVALID_REQUEST 로 거부해 이 분기에 닿지 않는다 — 여기서 다루지 않는다.

    private static PropertySearchRequest ratioRequest(String sort, String cursor, int size, String district,
            ContractType contractType, Long depositMin, Long depositMax, Long monthlyRentMax,
            PropertyType propertyType, List<RiskGrade> grades, BigDecimal areaMin, BigDecimal areaMax) {
        return new PropertySearchRequest(district, contractType, depositMin, depositMax, monthlyRentMax,
                propertyType, grades, areaMin, areaMax, null, null, null, null, null, null, null,
                sort, cursor, size);
    }

    private static PropertySearchRequest ratioRequest(String sort, String cursor, int size) {
        return ratioRequest(sort, cursor, size, null, null, null, null, null, null, null, null, null);
    }

    private static PropertyListResponse ratioRow(long propertyId, String ratio) {
        return new PropertyListResponse(propertyId, "강남구", "역삼동 100-1", PropertyType.APARTMENT,
                ContractType.DEPOSIT_ONLY, 300_000_000L, 0L, new BigDecimal("59.90"), 3,
                null, ratio == null ? null : new BigDecimal(ratio), OffsetDateTime.now(ZoneOffset.UTC));
    }

    private List<PropertySearchCondition> capturedSelectList(int times) {
        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper, times(times)).selectList(captor.capture());
        return captor.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private CursorPage<PropertyListResponse> searchPage(PropertySearchRequest request) {
        return (CursorPage<PropertyListResponse>) service.search(request);
    }

    @Test
    @DisplayName("전세가율순 · 자치구 없음 · 매물 조건 필터 없음이면 인덱스 매퍼로 읽고 selectList 는 부르지 않는다")
    void leaseRatioListWithoutFiltersUsesIndexMapper() {
        when(propertyMapper.selectListByLeaseRatioIndex(any()))
                .thenReturn(List.of(ratioRow(1L, "10.00"), ratioRow(2L, "20.00"), ratioRow(3L, "30.00")));

        service.search(ratioRequest("debtRatio,asc", null, 2));
        service.search(ratioRequest("debtRatio,desc", null, 2));

        verify(propertyMapper, times(2)).selectListByLeaseRatioIndex(any());
        verify(propertyMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("등급 필터만 있으면 인덱스 매퍼를 쓰고 조건을 그대로 넘긴다")
    void leaseRatioListWithRiskGradeFilterStillUsesIndexMapper() {
        when(propertyMapper.selectListByLeaseRatioIndex(any()))
                .thenReturn(List.of(ratioRow(1L, "10.00"), ratioRow(2L, "20.00"), ratioRow(3L, "30.00")));

        service.search(ratioRequest("debtRatio,asc", null, 2, null, null, null, null, null, null,
                List.of(RiskGrade.SAFE), null, null));

        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper).selectListByLeaseRatioIndex(captor.capture());
        assertThat(captor.getValue().riskGrades()).containsExactly(RiskGrade.SAFE);
        verify(propertyMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("자치구가 없으면 계약 · 유형 · 보증금 · 월세 · 면적 필터가 하나라도 있어도 인덱스 매퍼로 읽는다")
    void leaseRatioListWithAnyPropertyFilterUsesIndexMapper() {
        when(propertyMapper.selectListByLeaseRatioIndex(any())).thenReturn(List.of());
        String s = "debtRatio,asc";
        List<PropertySearchRequest> requests = List.of(
                ratioRequest(s, null, 2, null, ContractType.DEPOSIT_ONLY, null, null, null, null, null, null, null),
                ratioRequest(s, null, 2, null, null, 1L, null, null, null, null, null, null),
                ratioRequest(s, null, 2, null, null, null, 2L, null, null, null, null, null),
                ratioRequest(s, null, 2, null, null, null, null, 3L, null, null, null, null),
                ratioRequest(s, null, 2, null, null, null, null, null, PropertyType.OFFICETEL, null, null, null),
                ratioRequest(s, null, 2, null, null, null, null, null, null, null, BigDecimal.ONE, null),
                ratioRequest(s, null, 2, null, null, null, null, null, null, null, null, BigDecimal.TEN),
                ratioRequest(s, null, 2, null, ContractType.MONTHLY_RENT, 1L, 2L, 3L, PropertyType.APARTMENT,
                        List.of(RiskGrade.SAFE, RiskGrade.CAUTION), BigDecimal.ONE, BigDecimal.TEN));

        requests.forEach(service::search);

        verify(propertyMapper, times(requests.size())).selectListByLeaseRatioIndex(any());
        // 앞부분이 비어 끝부분을 이어 읽는다 — 이 selectList 는 인덱스 매퍼 뒤의 이어 읽기다.
        verify(propertyMapper, times(requests.size())).selectList(any());
    }

    @Test
    @DisplayName("자치구가 있으면 전세가율순이어도 필터와 관계없이 selectList 로만 읽는다")
    void leaseRatioListWithDistrictUsesSelectList() {
        when(propertyMapper.selectList(any())).thenReturn(List.of());

        service.search(ratioRequest("debtRatio,asc", null, 2, "강남구", null, null, null, null, null, null, null, null));
        service.search(ratioRequest("debtRatio,desc", null, 2, "강남구", ContractType.DEPOSIT_ONLY, 1L, 2L, 3L,
                PropertyType.OFFICETEL, List.of(RiskGrade.SAFE), BigDecimal.ONE, BigDecimal.TEN));

        verify(propertyMapper, never()).selectListByLeaseRatioIndex(any());
        capturedSelectList(2);
    }

    @Test
    @DisplayName("필터가 있을 때 앞부분이 모자라면 끝부분 selectList 에 같은 필터와 경계 커서를 넘기고, 앞부분 조회에도 같은 필터가 간다")
    void tailReadKeepsSameFilterAsHeadRead() {
        when(propertyMapper.selectListByLeaseRatioIndex(any()))
                .thenReturn(List.of(ratioRow(1L, "10.00")));
        when(propertyMapper.selectList(any())).thenReturn(List.of(ratioRow(9L, null)));
        PropertySearchRequest request = ratioRequest("debtRatio,asc", null, 3, null, ContractType.MONTHLY_RENT,
                1L, 2L, 3L, PropertyType.OFFICETEL, List.of(RiskGrade.SAFE), BigDecimal.ONE, BigDecimal.TEN);

        CursorPage<PropertyListResponse> page = searchPage(request);

        ArgumentCaptor<PropertySearchCondition> head = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper).selectListByLeaseRatioIndex(head.capture());
        PropertySearchCondition tail = capturedSelectList(1).get(0);
        for (PropertySearchCondition c : List.of(head.getValue(), tail)) {
            assertThat(c.district()).isNull();
            assertThat(c.contractType()).isEqualTo(ContractType.MONTHLY_RENT);
            assertThat(c.depositMin()).isEqualTo(1L);
            assertThat(c.depositMax()).isEqualTo(2L);
            assertThat(c.monthlyRentMax()).isEqualTo(3L);
            assertThat(c.propertyType()).isEqualTo(PropertyType.OFFICETEL);
            assertThat(c.riskGrades()).containsExactly(RiskGrade.SAFE);
            assertThat(c.areaMin()).isEqualByComparingTo("1");
            assertThat(c.areaMax()).isEqualByComparingTo("10");
        }
        assertThat(head.getValue().limit()).isEqualTo(4);
        assertThat(tail.limit()).isEqualTo(3); // 4 - 앞부분 1건
        assertThat(tail.lastDebtRatio()).isEqualByComparingTo("1000");
        assertThat(tail.lastId()).isEqualTo(Long.MIN_VALUE);
        assertThat(page.items()).extracting(PropertyListResponse::propertyId).containsExactly(1L, 9L);
    }

    @Test
    @DisplayName("전세가율이 아닌 정렬이면 필터가 없어도 selectList 로 읽는다")
    void otherSortUsesSelectList() {
        when(propertyMapper.selectList(any())).thenReturn(List.of());

        service.search(ratioRequest("deposit,asc", null, 2));
        service.search(ratioRequest(null, null, 2));

        verify(propertyMapper, never()).selectListByLeaseRatioIndex(any());
        capturedSelectList(2);
    }

    @Test
    @DisplayName("앞부분이 limit 보다 적으면 오름은 (1000, Long.MIN) 커서와 남은 건수로 selectList 를 이어 부르고 합쳐 돌려준다")
    void ascAppendsTailWithBoundaryCursorAndRemainingLimit() {
        // size 3 → limit 4. 앞부분 2건 → 남은 2건.
        when(propertyMapper.selectListByLeaseRatioIndex(any()))
                .thenReturn(List.of(ratioRow(1L, "10.00"), ratioRow(2L, "20.00")));
        when(propertyMapper.selectList(any()))
                .thenReturn(List.of(ratioRow(8L, null), ratioRow(9L, null)));

        CursorPage<PropertyListResponse> page = searchPage(ratioRequest("debtRatio,asc", null, 3));

        PropertySearchCondition tail = capturedSelectList(1).get(0);
        assertThat(tail.limit()).isEqualTo(2);
        assertThat(tail.ascending()).isTrue();
        assertThat(tail.lastDebtRatio()).isEqualByComparingTo("1000");
        assertThat(tail.lastId()).isEqualTo(Long.MIN_VALUE);
        assertThat(page.items()).extracting(PropertyListResponse::propertyId).containsExactly(1L, 2L, 8L);
        assertThat(page.hasNext()).isTrue();
        CursorCodec.Cursor next = CursorCodec.decode(page.nextCursor());
        assertThat(next.id()).isEqualTo(8L);
        assertThat(next.v()).isEqualTo("1000");
        assertThat(next.s()).isEqualTo("DEBT_RATIO,asc");
    }

    @Test
    @DisplayName("내림은 (-1000, Long.MAX) 커서로 이어 부르고, 끝부분이 비면 앞부분만으로 hasNext=false 다")
    void descAppendsTailWithBoundaryCursorAndEndsWhenTailEmpty() {
        when(propertyMapper.selectListByLeaseRatioIndex(any()))
                .thenReturn(List.of(ratioRow(1L, "90.00"), ratioRow(2L, "80.00")));
        when(propertyMapper.selectList(any())).thenReturn(List.of());

        CursorPage<PropertyListResponse> page = searchPage(ratioRequest("debtRatio,desc", null, 3));

        PropertySearchCondition tail = capturedSelectList(1).get(0);
        assertThat(tail.limit()).isEqualTo(2);
        assertThat(tail.ascending()).isFalse();
        assertThat(tail.lastDebtRatio()).isEqualByComparingTo("-1000");
        assertThat(tail.lastId()).isEqualTo(Long.MAX_VALUE);
        assertThat(page.items()).extracting(PropertyListResponse::propertyId).containsExactly(1L, 2L);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("앞부분이 limit 건을 다 채우면 끝부분을 부르지 않고 다음 커서는 마지막 앞부분 행이다")
    void fullHeadSkipsTailAndCursorPointsAtHead() {
        when(propertyMapper.selectListByLeaseRatioIndex(any()))
                .thenReturn(List.of(ratioRow(1L, "10.00"), ratioRow(2L, "20.00"), ratioRow(3L, "30.00")));

        CursorPage<PropertyListResponse> page = searchPage(ratioRequest("debtRatio,asc", null, 2));

        verify(propertyMapper, never()).selectList(any());
        assertThat(page.items()).extracting(PropertyListResponse::propertyId).containsExactly(1L, 2L);
        assertThat(page.hasNext()).isTrue();
        CursorCodec.Cursor next = CursorCodec.decode(page.nextCursor());
        assertThat(next.id()).isEqualTo(2L);
        assertThat(next.v()).isEqualTo("20.00");
    }

    @Test
    @DisplayName("끝부분 커서(오름 ≥ 1000 · 내림 ≤ -1000)는 인덱스 매퍼 없이 받은 조건 그대로 selectList 로 간다")
    void cursorInTailGoesStraightToSelectList() {
        when(propertyMapper.selectList(any())).thenReturn(List.of(ratioRow(9L, null)));

        service.search(ratioRequest("debtRatio,asc", CursorCodec.encode("DEBT_RATIO,asc", "1000", 8L), 3));
        service.search(ratioRequest("debtRatio,asc", CursorCodec.encode("DEBT_RATIO,asc", "1000.01", 8L), 3));
        service.search(ratioRequest("debtRatio,desc", CursorCodec.encode("DEBT_RATIO,desc", "-1000", 8L), 3));
        service.search(ratioRequest("debtRatio,desc", CursorCodec.encode("DEBT_RATIO,desc", "-1000.01", 8L), 3));

        verify(propertyMapper, never()).selectListByLeaseRatioIndex(any());
        List<PropertySearchCondition> calls = capturedSelectList(4);
        assertThat(calls).extracting(PropertySearchCondition::lastId).containsOnly(8L);
        assertThat(calls).extracting(PropertySearchCondition::limit).containsOnly(4);
    }

    @Test
    @DisplayName("끝부분 커서에 필터가 있어도 인덱스 매퍼 없이 필터 그대로 selectList 로 간다")
    void cursorInTailWithFilterGoesStraightToSelectListKeepingFilter() {
        when(propertyMapper.selectList(any())).thenReturn(List.of(ratioRow(9L, null)));

        service.search(ratioRequest("debtRatio,asc", CursorCodec.encode("DEBT_RATIO,asc", "1000", 8L), 3, null,
                ContractType.DEPOSIT_ONLY, 1L, null, null, null, null, null, null));

        verify(propertyMapper, never()).selectListByLeaseRatioIndex(any());
        PropertySearchCondition call = capturedSelectList(1).get(0);
        assertThat(call.contractType()).isEqualTo(ContractType.DEPOSIT_ONLY);
        assertThat(call.depositMin()).isEqualTo(1L);
        assertThat(call.lastId()).isEqualTo(8L);
    }

    @Test
    @DisplayName("앞부분 안 커서(오름 999.99 · 내림 -999.99)는 인덱스 매퍼로 가고 커서 값을 그대로 넘긴다")
    void cursorJustInsideHeadUsesIndexMapper() {
        when(propertyMapper.selectListByLeaseRatioIndex(any()))
                .thenReturn(List.of(ratioRow(1L, "1.00"), ratioRow(2L, "2.00"), ratioRow(3L, "3.00"),
                        ratioRow(4L, "4.00")));

        service.search(ratioRequest("debtRatio,asc", CursorCodec.encode("DEBT_RATIO,asc", "999.99", 5L), 3));
        service.search(ratioRequest("debtRatio,desc", CursorCodec.encode("DEBT_RATIO,desc", "-999.99", 5L), 3));

        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper, times(2)).selectListByLeaseRatioIndex(captor.capture());
        assertThat(captor.getAllValues()).extracting(PropertySearchCondition::lastId).containsOnly(5L);
        assertThat(captor.getAllValues().get(0).lastDebtRatio()).isEqualByComparingTo("999.99");
        assertThat(captor.getAllValues().get(1).lastDebtRatio()).isEqualByComparingTo("-999.99");
        verify(propertyMapper, never()).selectList(any());
    }
}
