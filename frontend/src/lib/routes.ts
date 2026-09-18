// 화면을 가리키는 경로. 라우트 표 자체는 app/router.tsx가 갖고, 여기는 그 경로를 만들고 읽는
// 순수 함수다 — app은 main.tsx 외에 아무도 import하지 않으므로(frontend/CLAUDE.md import 방향)
// 여러 화면이 함께 쓰는 경로 조립은 누구나 쓰는 바닥층에 둔다.
//
// 상세는 별도 화면이 아니라 지도 옆 패널이라(매물 API 명세 1.4) 「그 매물의 상세」를 가리키는
// 경로가 지도 경로 + 검색 파라미터 하나다. 파라미터 이름을 화면마다 적으면 한쪽만 고쳐진다 —
// 읽는 쪽(MapPage)과 만드는 쪽(관심 매물 · 알림 · 최근 등록 매물)이 이 파일 하나를 본다.

/** 지도 탐색 화면 */
export const MAP_PATH = '/map';

/** 지도 화면이 상세 패널로 여는 매물. URL에 올리는 것은 이 하나다 — 필터 · 단계 · 확대 수준은 로컬 상태다 */
export const DETAIL_PROPERTY_PARAM = 'propertyId';

/** 그 매물의 상세 패널이 열린 지도 화면 경로. 라우터 Link의 to에 넣는다 */
export const propertyDetailPath = (propertyId: number): string =>
  `${MAP_PATH}?${DETAIL_PROPERTY_PARAM}=${propertyId}`;

/**
 * 검색 파라미터에서 상세로 열 매물 번호를 읽는다.
 * 비어 있거나 숫자가 아니거나 매물 번호가 될 수 없는 값(0 이하 · 소수)이면 없는 것으로 다룬다.
 */
export function readDetailPropertyId(params: URLSearchParams): number | null {
  const raw = params.get(DETAIL_PROPERTY_PARAM);
  if (raw === null || raw.trim() === '') return null;
  const parsed = Number(raw);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

/** 검색 파라미터에서 상세 매물 번호만 뺀 사본. 나머지 파라미터는 그대로 둔다 */
export function withoutDetailPropertyId(params: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(params);
  next.delete(DETAIL_PROPERTY_PARAM);
  return next;
}

/** 검색 파라미터에 상세 매물 번호를 얹은 사본. 나머지 파라미터는 그대로 둔다 */
export function withDetailPropertyId(params: URLSearchParams, propertyId: number): URLSearchParams {
  const next = new URLSearchParams(params);
  next.set(DETAIL_PROPERTY_PARAM, String(propertyId));
  return next;
}
