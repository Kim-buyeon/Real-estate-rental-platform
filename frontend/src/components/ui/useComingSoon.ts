import { useCallback } from 'react';
import { useToast } from './useToast';

/** 아직 만들지 않은 기능의 입구를 눌렀을 때의 문구. 화면마다 다시 적지 않는다 */
export const COMING_SOON_MESSAGE = '아직 준비 중인 기능입니다';

/**
 * 준비 중 입구(차기 범위 기능 · 엔드포인트가 없는 기능)를 눌렀을 때 부르는 함수를 돌려준다.
 * 이동하지 않고 토스트 한 장만 띄운다. 돌려주는 함수는 렌더마다 같다 — memo 된 자식에 그대로 내려도 된다.
 */
export function useComingSoon(): () => void {
  const { show } = useToast();
  return useCallback(() => show(COMING_SOON_MESSAGE), [show]);
}
