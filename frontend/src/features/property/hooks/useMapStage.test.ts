// useMapStage 단계 전이 검증. 지도 SDK와 무관한 순수 화면 로직이다 — frontend/CLAUDE.md 상태.
import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useMapStage } from './useMapStage';

describe('useMapStage', () => {
  it('초기 단계는 서울 전체다', () => {
    const { result } = renderHook(() => useMapStage());

    expect(result.current.stage).toEqual({ type: 'seoul' });
  });

  it('selectDistrict는 그 자치구 단계로 전이한다', () => {
    const { result } = renderHook(() => useMapStage());

    act(() => {
      result.current.selectDistrict('강서구');
    });

    expect(result.current.stage).toEqual({ type: 'district', district: '강서구' });
  });

  it('backToSeoul은 자치구 단계에서 서울 전체 단계로 되돌린다', () => {
    const { result } = renderHook(() => useMapStage());

    act(() => {
      result.current.selectDistrict('강서구');
    });
    act(() => {
      result.current.backToSeoul();
    });

    expect(result.current.stage).toEqual({ type: 'seoul' });
  });
});
