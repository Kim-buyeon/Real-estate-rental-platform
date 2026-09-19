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

/** 로그인 화면이 로그인 뒤 돌려보낼 경로를 읽는 파라미터. 싣는 쪽은 인증 가드와 헤더 로그인 링크다 */
export const LOGIN_REDIRECT_PARAM = 'redirect';

/**
 * 로그인 뒤 returnTo(경로 + 검색 파라미터)로 돌아오는 로그인 화면 경로. 받는 쪽(LoginPage)이
 * 같은 오리진 경로인지 다시 거른다 — 여기서 거르지 않는다.
 */
export const loginPath = (returnTo: string): string =>
  `/login?${LOGIN_REDIRECT_PARAM}=${encodeURIComponent(returnTo)}`;

/** 로그인 화면이 「가입 완료」 안내를 띄우는 파라미터. 싣는 쪽은 가입 화면이다 — 가입 응답에 토큰이 없다(명세 1.2) */
export const LOGIN_SIGNED_UP_PARAM = 'signedUp';

/** 로그인 화면이 「비밀번호 변경 완료」 안내를 띄우는 파라미터. 싣는 쪽은 재설정 확정 화면이다 — 재설정은 로그인을 대신하지 않는다(명세 1.3) */
export const LOGIN_PASSWORD_RESET_PARAM = 'passwordReset';

/** 안내 파라미터의 값. 켜짐 하나뿐이다 */
const LOGIN_NOTICE_ON = '1';

/** 가입 뒤 보내는 로그인 화면 경로 */
export const loginAfterSignupPath = (): string => `/login?${LOGIN_SIGNED_UP_PARAM}=${LOGIN_NOTICE_ON}`;

/** 비밀번호 재설정 뒤 보내는 로그인 화면 경로 */
export const loginAfterPasswordResetPath = (): string =>
  `/login?${LOGIN_PASSWORD_RESET_PARAM}=${LOGIN_NOTICE_ON}`;

/** 가입 뒤 들어온 로그인 화면인가 */
export const isLoginAfterSignup = (params: URLSearchParams): boolean =>
  params.get(LOGIN_SIGNED_UP_PARAM) === LOGIN_NOTICE_ON;

/** 비밀번호 재설정 뒤 들어온 로그인 화면인가 */
export const isLoginAfterPasswordReset = (params: URLSearchParams): boolean =>
  params.get(LOGIN_PASSWORD_RESET_PARAM) === LOGIN_NOTICE_ON;

/**
 * 재설정 확정 화면이 토큰을 읽는 파라미터. 싣는 쪽은 서버가 보내는 메일의 링크다 —
 * 명세 1.3 「본문의 링크는 화면 경로 /password-reset/confirm?token=<토큰>」
 */
export const PASSWORD_RESET_TOKEN_PARAM = 'token';

/** 검색 파라미터에서 재설정 토큰을 읽는다. 없거나 공백뿐이면 null */
export function readPasswordResetToken(params: URLSearchParams): string | null {
  const token = params.get(PASSWORD_RESET_TOKEN_PARAM)?.trim();
  return token ? token : null;
}
