package com.duri.rentalplatform.domain.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.entity.PropertyCode;
import com.duri.rentalplatform.domain.property.enums.CodeGroup;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.repository.PropertyCodeRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.domain.property.vo.PropertyRegistration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link PropertyLoadWriter} 검증. 저장은 JPA(스텁 저장소로 확인)이고, 이 클래스가 실제로 하는 일은
 * 코드 캐시({@code findCode})와 FK 연결이다 — 클래스 Javadoc.
 *
 * <p>{@code @Transactional} 경계 자체(커밋 · 롤백)는 프록시가 있어야 확인되므로 이 단위 테스트의
 * 범위 밖이다. 여기서는 캐시 동작 · 코드 연결 · 시드 누락 처리 · 자연키 조회만 본다.
 */
class PropertyLoadWriterTest {

    private PropertyRepository propertyRepository;
    private PropertyCodeRepository propertyCodeRepository;
    private PropertyLoadWriter writer;

    @BeforeEach
    void setUp() {
        propertyRepository = mock(PropertyRepository.class);
        propertyCodeRepository = mock(PropertyCodeRepository.class);
        writer = new PropertyLoadWriter(propertyRepository, propertyCodeRepository);
    }

    private PropertyRegistration registrationOf(String address, Integer floor) {
        return new PropertyRegistration(
                address, "강남구", "김민준", ContractType.DEPOSIT_ONLY, PropertyType.APARTMENT,
                300_000_000L, 0L, 300_000_000L, PriceType.ACTUAL_TRANSACTION, LocalDate.now(),
                new BigDecimal("59.90"), floor, 2005,
                new BigDecimal("37.5000000"), new BigDecimal("127.0000000"));
    }

    @Test
    @DisplayName("같은 코드값 조회는 saveAll을 여러 번 걸쳐도 저장소에 한 번만 묻는다")
    void cachesCodeLookupAcrossMultipleSaveAllCalls() {
        PropertyCode contractCode = mock(PropertyCode.class);
        PropertyCode propertyTypeCode = mock(PropertyCode.class);
        PropertyCode statusCode = mock(PropertyCode.class);
        when(contractCode.getCodeId()).thenReturn(1L);
        when(propertyTypeCode.getCodeId()).thenReturn(2L);
        when(statusCode.getCodeId()).thenReturn(3L);
        when(propertyCodeRepository.findByCodeGroupAndCodeValue(CodeGroup.CONTRACT_TYPE, "DEPOSIT_ONLY"))
                .thenReturn(Optional.of(contractCode));
        when(propertyCodeRepository.findByCodeGroupAndCodeValue(CodeGroup.PROPERTY_TYPE, "APARTMENT"))
                .thenReturn(Optional.of(propertyTypeCode));
        when(propertyCodeRepository.findByCodeGroupAndCodeValue(CodeGroup.PROPERTY_STATUS, "AVAILABLE"))
                .thenReturn(Optional.of(statusCode));
        when(propertyCodeRepository.getReferenceById(1L)).thenReturn(contractCode);
        when(propertyCodeRepository.getReferenceById(2L)).thenReturn(propertyTypeCode);
        when(propertyCodeRepository.getReferenceById(3L)).thenReturn(statusCode);

        writer.saveAll(List.of(registrationOf("주소1", 1)));
        writer.saveAll(List.of(registrationOf("주소2", 2), registrationOf("주소3", 3)));

        // 세 매물 모두 같은 (CONTRACT_TYPE,DEPOSIT_ONLY) 등 같은 조합을 쓴다. saveAll 을 두 번
        // 걸쳐도 저장소 조회는 조합마다 한 번뿐이어야 한다 — 캐시가 인스턴스 생애 동안 유지된다.
        verify(propertyCodeRepository, times(1))
                .findByCodeGroupAndCodeValue(CodeGroup.CONTRACT_TYPE, "DEPOSIT_ONLY");
        verify(propertyCodeRepository, times(1))
                .findByCodeGroupAndCodeValue(CodeGroup.PROPERTY_TYPE, "APARTMENT");
        verify(propertyCodeRepository, times(1))
                .findByCodeGroupAndCodeValue(CodeGroup.PROPERTY_STATUS, "AVAILABLE");
        // 엔티티 자체는 캐시하지 않으므로 getReferenceById 는 매물마다 다시 불린다(총 3매물).
        verify(propertyCodeRepository, times(3)).getReferenceById(1L);
    }

    @Test
    @DisplayName("공통 코드 시드가 없으면 IllegalStateException이고 매물은 저장되지 않는다")
    void throwsIllegalStateExceptionWhenCodeSeedIsMissing() {
        when(propertyCodeRepository.findByCodeGroupAndCodeValue(CodeGroup.CONTRACT_TYPE, "DEPOSIT_ONLY"))
                .thenReturn(Optional.empty());
        List<PropertyRegistration> registrations = List.of(registrationOf("주소1", 1));

        // BusinessException 이 아니다 — 시드 누락은 업무 규칙 위반이 아니라 기동 구성 문제라서다.
        assertThatThrownBy(() -> writer.saveAll(registrations))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공통 코드 시드가 없다");
        verify(propertyRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("saveAll은 등록값을 코드가 연결된 매물로 변환해 저장하고 건수를 반환한다")
    void mapsRegistrationsToPropertiesWithResolvedCodesAndReturnsCount() {
        PropertyCode contractCode = mock(PropertyCode.class);
        PropertyCode propertyTypeCode = mock(PropertyCode.class);
        PropertyCode statusCode = mock(PropertyCode.class);
        when(contractCode.getCodeId()).thenReturn(10L);
        when(propertyTypeCode.getCodeId()).thenReturn(20L);
        when(statusCode.getCodeId()).thenReturn(30L);
        when(propertyCodeRepository.findByCodeGroupAndCodeValue(CodeGroup.CONTRACT_TYPE, "DEPOSIT_ONLY"))
                .thenReturn(Optional.of(contractCode));
        when(propertyCodeRepository.findByCodeGroupAndCodeValue(CodeGroup.PROPERTY_TYPE, "APARTMENT"))
                .thenReturn(Optional.of(propertyTypeCode));
        when(propertyCodeRepository.findByCodeGroupAndCodeValue(CodeGroup.PROPERTY_STATUS, "AVAILABLE"))
                .thenReturn(Optional.of(statusCode));
        when(propertyCodeRepository.getReferenceById(10L)).thenReturn(contractCode);
        when(propertyCodeRepository.getReferenceById(20L)).thenReturn(propertyTypeCode);
        when(propertyCodeRepository.getReferenceById(30L)).thenReturn(statusCode);

        List<PropertyRegistration> registrations =
                List.of(registrationOf("서울특별시 강남구 역삼동 100-1", 3));

        int saved = writer.saveAll(registrations);

        assertThat(saved).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Property>> captor = ArgumentCaptor.forClass(List.class);
        verify(propertyRepository).saveAll(captor.capture());
        Property property = captor.getValue().get(0);

        assertThat(property.getAddress()).isEqualTo("서울특별시 강남구 역삼동 100-1");
        assertThat(property.getContractTypeCode()).isSameAs(contractCode);
        assertThat(property.getPropertyTypeCode()).isSameAs(propertyTypeCode);
        assertThat(property.getStatusCode()).isSameAs(statusCode);
    }

    @Test
    @DisplayName("자치구의 기존 매물을 자연키 집합으로 변환한다")
    void mapsExistingPropertiesInADistrictToNaturalKeys() {
        PropertyCode anyCode = mock(PropertyCode.class);
        PropertyRegistration reg1 = registrationOf("주소1", 1);
        PropertyRegistration reg2 = registrationOf("주소2", 2);
        Property property1 = Property.register(reg1, anyCode, anyCode, anyCode);
        Property property2 = Property.register(reg2, anyCode, anyCode, anyCode);
        when(propertyRepository.findAllByDistrict("강남구")).thenReturn(List.of(property1, property2));

        Set<PropertyNaturalKey> naturalKeys = writer.findLoadedNaturalKeys("강남구");

        assertThat(naturalKeys).containsExactlyInAnyOrder(reg1.naturalKey(), reg2.naturalKey());
    }
}
