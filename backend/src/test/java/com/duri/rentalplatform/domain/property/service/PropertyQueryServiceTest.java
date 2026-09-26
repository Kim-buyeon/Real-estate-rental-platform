package com.duri.rentalplatform.domain.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.CursorPage;
import com.duri.rentalplatform.common.ErrorCode;
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
import com.duri.rentalplatform.domain.property.mapper.PropertyMapper;
import com.duri.rentalplatform.domain.property.store.DistrictCountCacheStore;
import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import com.duri.rentalplatform.domain.property.vo.MapClusterCellRow;
import com.duri.rentalplatform.domain.property.vo.PropertyDetailRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
    private DistrictCountCacheStore districtCountCacheStore;
    private PropertyQueryService service;

    @BeforeEach
    void setUp() {
        propertyMapper = mock(PropertyMapper.class);
        districtCountCacheStore = mock(DistrictCountCacheStore.class);
        service = new PropertyQueryService(propertyMapper, districtCountCacheStore);
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

    @Test
    @DisplayName("반경 조건은 바운딩 박스로 1차 조회하고 반경 밖을 제거해 거리순으로 반환한다")
    void searchMarkersInRadiusFiltersAndSortsByDistance() {
        double lat = 37.5;
        double lng = 127.0;
        double radiusKm = 1.0;
        // near: 중심에서 약 0.03km. far: 중심에서 약 0.08km. outside: 바운딩 박스 모서리 근처, 반경(1km) 밖.
        PropertyMarkerResponse near = marker(1L, "37.5003", "127.0000");
        PropertyMarkerResponse far = marker(2L, "37.5007", "127.0000");
        PropertyMarkerResponse outside = marker(3L, "37.5100", "127.0100");
        // 매퍼가 이미 정렬돼 있지 않은 순서로 돌려줘도 서비스가 거리순으로 다시 정렬해야 한다.
        when(propertyMapper.selectMarkers(any())).thenReturn(List.of(far, outside, near));

        Object result = service.search(radiusRequest(lat, lng, radiusKm));

        PropertyMarkersResponse response = (PropertyMarkersResponse) result;
        assertThat(response.items()).extracting(PropertyMarkerResponse::propertyId)
                .containsExactly(1L, 2L);

        BoundingBox expectedBox = GeoDistanceCalculator.boundingBox(lat, lng, radiusKm);
        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper).selectMarkers(captor.capture());
        assertThat(captor.getValue().minLat()).isEqualTo(expectedBox.minLat());
        assertThat(captor.getValue().maxLat()).isEqualTo(expectedBox.maxLat());
        assertThat(captor.getValue().minLng()).isEqualTo(expectedBox.minLng());
        assertThat(captor.getValue().maxLng()).isEqualTo(expectedBox.maxLng());
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
        when(districtCountCacheStore.find(filter)).thenReturn(Optional.of(cached));

        DistrictCountsResponse result = service.getDistrictCounts(filter);

        assertThat(result).isEqualTo(cached);
        verify(propertyMapper, never()).selectDistrictCounts(any());
        verify(districtCountCacheStore, never()).save(any(), any());
    }

    @Test
    @DisplayName("자치구 집계는 캐시가 비어 있으면 매퍼로 조회하고 캐시에 저장한다")
    void districtCountsCacheMissQueriesMapperAndSaves() {
        DistrictCountRequest filter = new DistrictCountRequest(
                null, null, null, null, null, null, null, null, null);
        when(districtCountCacheStore.find(filter)).thenReturn(Optional.empty());
        when(propertyMapper.selectDistrictCounts(any())).thenReturn(List.of());

        DistrictCountsResponse result = service.getDistrictCounts(filter);

        assertThat(result.totalCount()).isZero();
        verify(propertyMapper).selectDistrictCounts(any());
        verify(districtCountCacheStore).save(eq(filter), any());
    }

    // ---------- 지도 묶음 (명세 1.12) ----------

    private static PropertyMapClustersRequest clustersRequest(
            Double minLat, Double maxLat, Double minLng, Double maxLng) {
        return new PropertyMapClustersRequest("강서구", null, null, null, null, null, null, null, null,
                minLat, maxLat, minLng, maxLng);
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
    @DisplayName("지도 묶음: 격자 집계에 칸 크기 = 영역 ÷ 12, 마지막 칸 번호 11 을 넘긴다")
    void mapClustersPassesGridToMapper() {
        when(propertyMapper.selectClusterCells(any())).thenReturn(List.of());

        service.getMapClusters(exampleClustersRequest());

        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper).selectClusterCells(captor.capture());
        PropertySearchCondition condition = captor.getValue();
        assertThat(condition.cellLat()).isCloseTo((37.58 - 37.52) / 12, Offset.offset(1e-12));
        assertThat(condition.cellLng()).isCloseTo((126.89 - 126.81) / 12, Offset.offset(1e-12));
        assertThat(condition.maxCellIndex()).isEqualTo(PropertyQueryService.GRID_DIVISIONS - 1);
        assertThat(condition.district()).isEqualTo("강서구");
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
    }
}
