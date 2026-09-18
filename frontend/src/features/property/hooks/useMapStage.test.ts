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

  // 지도 단계가 튀지 않는 경계 ② (이슈 104 계획) — showDistrict가 이미 그 자치구일 때 새 상태
  // 객체를 만들면 MapExplorer의 단계 이동 효과가 다시 돌아 지도가 중심으로 튄다. 그 구 안에서
  // 지도를 움직인 뒤 목록 · 마커에서 상세를 열어도 보던 자리를 잃지 않으려면 참조가 그대로여야
  // 한다 — 값만 같은 새 객체로는 이 경계를 잡지 못한다(React가 갱신으로 본다).
  it('showDistrict는 이미 그 자치구면 상태 객체를 그대로 돌려준다', () => {
    const { result } = renderHook(() => useMapStage());

    act(() => {
      result.current.showDistrict('강서구');
    });
    const stageAfterFirstCall = result.current.stage;

    act(() => {
      result.current.showDistrict('강서구');
    });

    // 값이 같은 새 객체가 아니라 같은 참조다 — toEqual이 아니라 toBe로 잡는다
    expect(result.current.stage).toBe(stageAfterFirstCall);
  });

  // 위 경계가 「같을 때만 막는 것」이지 「항상 막는 것」이 아님을 함께 잡는다 — 이 단언이 없으면
  // showDistrict를 아예 no-op으로 만들어도(다른 자치구조차 옮기지 않아도) 위 테스트가 통과한다
  it('showDistrict는 다른 자치구를 주면 그 자치구로 옮긴다', () => {
    const { result } = renderHook(() => useMapStage());

    act(() => {
      result.current.showDistrict('강서구');
    });
    act(() => {
      result.current.showDistrict('구로구');
    });

    expect(result.current.stage).toEqual({ type: 'district', district: '구로구' });
  });

  // showDistrict(따라가기)와 selectDistrict(사용자가 직접 고름)의 차이 — 같은 구를 다시 줘도
  // selectDistrict는 「그 구로 되돌려 달라」는 요청이라 새 상태를 만드는 것이 의도다. 두 함수가
  // 다르게 동작하는 것이 설계임을 여기서 함께 남긴다.
  it('selectDistrict는 같은 자치구를 다시 골라도 새 상태 객체를 만든다 — showDistrict와 다르다', () => {
    const { result } = renderHook(() => useMapStage());

    act(() => {
      result.current.selectDistrict('강서구');
    });
    const stageAfterFirstCall = result.current.stage;

    act(() => {
      result.current.selectDistrict('강서구');
    });

    expect(result.current.stage).toEqual({ type: 'district', district: '강서구' });
    expect(result.current.stage).not.toBe(stageAfterFirstCall);
  });
});
