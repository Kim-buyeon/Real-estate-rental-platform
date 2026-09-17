// MarkerPreviewCard 검증 — 상세 패널이 열리는 자리는 이 카드의 「상세 보기」 하나다
// (매물 API 명세 1.4 「카드의 상세 보기 선택 → 지도 옆 패널에 상세 표시」). 그 호출과
// 미분석 매물(riskGrade · debtRatio가 null)의 표기를 확인한다.
//
// 카드가 지도 위에 얹히는 경로(마커 선택 → 오버레이)는 카카오맵 SDK가 있어야 하므로 여기서 다루지
// 않는다 — PropertyDetailPanel.test.tsx 머리말과 같은 이유다. props만 받는 컴포넌트라 네트워크도 없다.
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { PropertyMarker } from '../../../api/property';
import { MarkerPreviewCard } from './MarkerPreviewCard';

/** 매물 API 명세 1.4 응답 예시 그대로 */
const MARKER: PropertyMarker = {
  propertyId: 1024,
  latitude: 37.5501234,
  longitude: 126.8497561,
  deposit: 230000000,
  riskGrade: 'SAFE',
  contractType: 'DEPOSIT_ONLY',
  monthlyRent: 0,
  district: '강서구',
  debtRatio: 68.0,
  hasSeniorDebt: true,
};

function renderCard(marker: PropertyMarker = MARKER) {
  const onClose = vi.fn();
  const onOpenDetail = vi.fn();
  render(<MarkerPreviewCard marker={marker} onClose={onClose} onOpenDetail={onOpenDetail} />);
  return { onClose, onOpenDetail };
}

describe('MarkerPreviewCard', () => {
  it('「상세 보기」를 누르면 그 매물 식별자로 상세 패널 열기를 요청한다', () => {
    const { onOpenDetail } = renderCard();

    fireEvent.click(screen.getByRole('button', { name: '상세 보기' }));

    expect(onOpenDetail).toHaveBeenCalledWith(MARKER.propertyId);
  });

  it('닫기 버튼은 상세 패널을 열지 않고 카드만 닫는다', () => {
    const { onClose, onOpenDetail } = renderCard();

    fireEvent.click(screen.getByRole('button', { name: '미리보기 닫기' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onOpenDetail).not.toHaveBeenCalled();
  });

  it('분석 이력이 없는 매물은 등급과 전세가율을 미분석으로 표기한다', () => {
    renderCard({ ...MARKER, riskGrade: null, debtRatio: null });

    // 「미분석」은 등급 뱃지와 전세가율 두 곳에 나온다
    expect(screen.getAllByText('미분석')).toHaveLength(2);
  });
});
