package com.duri.rentalplatform.domain.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.entity.Wishlist;
import com.duri.rentalplatform.domain.property.enums.WishlistAlertCondition;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.repository.WishlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/** {@link WishlistCommandService} — 없는 매물, 중복의 두 경로, 멱등 해제, 저장 값. */
class WishlistCommandServiceTest {

    private static final long USER_ID = 42L;
    private static final long PROPERTY_ID = 1024L;

    private PropertyRepository propertyRepository;
    private WishlistRepository wishlistRepository;
    private WishlistCommandService service;

    @BeforeEach
    void setUp() {
        propertyRepository = mock(PropertyRepository.class);
        wishlistRepository = mock(WishlistRepository.class);
        service = new WishlistCommandService(propertyRepository, wishlistRepository);
    }

    @Test
    @DisplayName("매물이 없으면 PROPERTY_NOT_FOUND 이고 저장하지 않는다")
    void propertyNotFound() {
        when(propertyRepository.existsById(PROPERTY_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.add(USER_ID, PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROPERTY_NOT_FOUND);
        verify(wishlistRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("이미 등록했으면 WISHLIST_DUPLICATED 이고 저장하지 않는다")
    void duplicatedByCheck() {
        when(propertyRepository.existsById(PROPERTY_ID)).thenReturn(true);
        when(wishlistRepository.existsByUserIdAndPropertyId(USER_ID, PROPERTY_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.add(USER_ID, PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WISHLIST_DUPLICATED);
        verify(wishlistRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("확인을 통과했어도 유일 제약에 걸리면(동시 등록) WISHLIST_DUPLICATED")
    void duplicatedByConstraint() {
        when(propertyRepository.existsById(PROPERTY_ID)).thenReturn(true);
        when(wishlistRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq"));

        assertThatThrownBy(() -> service.add(USER_ID, PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WISHLIST_DUPLICATED);
    }

    @Test
    @DisplayName("등록은 토큰의 사용자 · 요청 매물로 모니터링 켬 · 위험도와 등기 조건을 저장한다")
    void savesValues() {
        when(propertyRepository.existsById(PROPERTY_ID)).thenReturn(true);

        service.add(USER_ID, PROPERTY_ID);

        ArgumentCaptor<Wishlist> captor = ArgumentCaptor.forClass(Wishlist.class);
        verify(wishlistRepository).saveAndFlush(captor.capture());
        Wishlist saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getPropertyId()).isEqualTo(PROPERTY_ID);
        assertThat(saved.isMonitoring()).isTrue();
        assertThat(saved.getAlertCondition()).isEqualTo(WishlistAlertCondition.RISK_AND_REGISTRY);
    }

    @Test
    @DisplayName("등록되지 않은 매물 해제도 예외 없이 끝난다(멱등)")
    void removeIsIdempotent() {
        when(wishlistRepository.deleteByUserIdAndPropertyId(USER_ID, PROPERTY_ID)).thenReturn(0L);

        assertThatCode(() -> service.remove(USER_ID, PROPERTY_ID)).doesNotThrowAnyException();
        verify(wishlistRepository).deleteByUserIdAndPropertyId(USER_ID, PROPERTY_ID);
    }
}
