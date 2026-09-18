/**
 * 라우트 표가 갖는 라우트의 성질. `AppShell` 이 `useMatches()` 로 읽는다 —
 * 경로 문자열 비교를 레이아웃에 두면 라우트의 성질이 라우트 표 밖에 하나 더 생긴다.
 */
export interface RouteHandle {
  /** 이 라우트에서는 공통 푸터를 렌더하지 않는다. 지도 화면은 남은 높이를 전부 쓴다 */
  hideFooter?: boolean;
}

/** `useMatches()` 가 주는 `handle` 은 `unknown` 이다. 단언 없이 좁혀서 읽는다 */
export function hidesFooter(handle: unknown): boolean {
  return typeof handle === 'object' && handle !== null && 'hideFooter' in handle && handle.hideFooter === true;
}
