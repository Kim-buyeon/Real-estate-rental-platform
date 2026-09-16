import { useCallback, useState } from 'react';

/** 지도 드릴다운 단계. 1 서울 전체 · 2 자치구 (3 상세는 패널이라 단계가 아니다 — 매물 API 명세 1.4) */
export type MapStage = { type: 'seoul' } | { type: 'district'; district: string };

/**
 * 단계 상태. 필터는 여기에 두지 않는다 — 단계를 오갈 때 유지되어야 하므로 지도 상태와 분리한다
 * (kakao-map 3장 되돌아가기 행).
 */
export function useMapStage() {
  const [stage, setStage] = useState<MapStage>({ type: 'seoul' });

  const selectDistrict = useCallback((district: string) => setStage({ type: 'district', district }), []);
  const backToSeoul = useCallback(() => setStage({ type: 'seoul' }), []);

  return { stage, selectDistrict, backToSeoul };
}
