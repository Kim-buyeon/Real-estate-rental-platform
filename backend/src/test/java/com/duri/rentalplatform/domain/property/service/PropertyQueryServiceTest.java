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
import com.duri.rentalplatform.domain.property.dto.condition.PropertySearchCondition;
import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.request.PropertySearchRequest;
import com.duri.rentalplatform.domain.property.dto.response.DistrictCountsResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyDetailResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyListResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkerResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkersResponse;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.mapper.PropertyMapper;
import com.duri.rentalplatform.domain.property.store.DistrictCountCacheStore;
import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import com.duri.rentalplatform.domain.property.vo.PropertyDetailRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
    @DisplayName("표시 영역 네 값이 모두 있으면 마커 목록과 count를 반환한다")
    void searchMarkersInBox() {
        List<PropertyMarkerResponse> markers = List.of(marker(1L, "37.50", "127.00"), marker(2L, "37.51", "127.01"));
        when(propertyMapper.selectMarkers(any())).thenReturn(markers);

        Object result = service.search(boxRequest(37.0, 38.0, 126.0, 128.0));

        assertThat(result).isInstanceOf(PropertyMarkersResponse.class);
        PropertyMarkersResponse response = (PropertyMarkersResponse) result;
        assertThat(response.count()).isEqualTo(2);
        assertThat(response.items()).containsExactlyElementsOf(markers);

        ArgumentCaptor<PropertySearchCondition> captor = ArgumentCaptor.forClass(PropertySearchCondition.class);
        verify(propertyMapper).selectMarkers(captor.capture());
        assertThat(captor.getValue().minLat()).isEqualTo(37.0);
        assertThat(captor.getValue().maxLat()).isEqualTo(38.0);
        assertThat(captor.getValue().minLng()).isEqualTo(126.0);
        assertThat(captor.getValue().maxLng()).isEqualTo(128.0);
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
    @DisplayName("표시 영역 네 값 중 일부만 있으면 INVALID_REQUEST(minLat)다")
    void rejectsPartialBox() {
        assertInvalidRequest(boxRequest(37.0, null, null, null), "minLat");
    }

    @Test
    @DisplayName("중심·반경 세 값 중 일부만 있으면 INVALID_REQUEST(lat)다")
    void rejectsPartialRadius() {
        assertInvalidRequest(radiusRequest(37.5, null, null), "lat");
    }

    @Test
    @DisplayName("표시 영역과 반경을 동시에 모두 채우면 INVALID_REQUEST(minLat)다 — 조건이 모호하다")
    void rejectsBothBoxAndRadiusFullyGiven() {
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
}
