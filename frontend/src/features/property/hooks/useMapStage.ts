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

  /**
   * 이미 정해진 자치구에 단계를 맞춘다 — 상세가 열릴 때 그 매물의 자치구를 따라가는 경로다
   * (딥링크로 들어오면 패널만 열리고 지도가 무관한 자리를 비추면 안 된다).
   *
   * 이미 그 자치구면 **같은 상태 객체를 그대로 돌려준다.** React가 그 갱신을 걸러 단계를 보고
   * 지도를 옮기는 효과가 다시 돌지 않는다 — 사용자가 그 구 안에서 지도를 움직인 뒤 상세를 열어도
   * 지도가 중심으로 튀지 않는다. 사용자가 직접 고르는 selectDistrict는 그대로 둔다: 같은 구를
   * 다시 고르는 것은 「그 구로 되돌려 달라」는 요청이라 다시 옮기는 쪽이 맞다.
   */
  const showDistrict = useCallback(
    (district: string) =>
      setStage((prev) =>
        prev.type === 'district' && prev.district === district ? prev : { type: 'district', district },
      ),
    [],
  );

  return { stage, selectDistrict, backToSeoul, showDistrict };
}
