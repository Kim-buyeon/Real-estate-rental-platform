// 토큰 보관. React를 모르는 모듈이다 — api/client가 읽고 쓰고, 컴포넌트는 useSession()으로 로그인 상태만 읽는다.
// 액세스 토큰은 메모리(모듈 변수), 리프레시 토큰은 localStorage — frontend/CLAUDE.md 세션.

const REFRESH_TOKEN_KEY = 'refreshToken';

interface Tokens {
  accessToken: string;
  refreshToken: string;
}

let accessToken: string | null = null;
const listeners = new Set<() => void>();

function readStoredRefreshToken(): string | null {
  try {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  } catch {
    return null;
  }
}

function notify() {
  listeners.forEach((listener) => listener());
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function getRefreshToken(): string | null {
  return readStoredRefreshToken();
}

export function setTokens(tokens: Tokens): void {
  accessToken = tokens.accessToken;
  try {
    localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken);
  } catch {
    // 저장소를 쓸 수 없는 환경(사생활 보호 모드 등)에서는 새로고침 뒤 로그인이 풀린다
  }
  notify();
}

export function clearSession(): void {
  const hadSession = accessToken !== null || readStoredRefreshToken() !== null;
  accessToken = null;
  try {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
  } catch {
    // 위와 같다
  }
  if (hadSession) notify();
}

/** 로그인 상태 — 액세스 토큰이 메모리에 있는가. 기동 시 복원(main.tsx)이 끝난 뒤에만 의미가 있다 */
export function isAuthenticated(): boolean {
  return accessToken !== null;
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
