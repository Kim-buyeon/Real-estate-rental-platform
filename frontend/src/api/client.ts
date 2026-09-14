// axios 인스턴스 하나와 request<T>() 하나. 봉투 · 오류 · 재발급 · 파라미터 직렬화가 여기 한 곳에 있다 — frontend/CLAUDE.md API 클라이언트.
// API 함수(api/<도메인>.ts)는 request<T>()만 부른다.

import axios, { type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import { clearSession, getAccessToken, getRefreshToken, setTokens } from '../session/store';
import type { ApiErrorBody, ApiResponse } from './types';

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 재발급 뒤 한 번 재시도한 요청 표시. 두 번째 재발급을 막는다 */
    isRetryAfterReissue?: boolean;
  }
}

const BASE_URL = '/api';
/** 위험도 조회가 외부 연동(서킷 · 폴백)을 거쳐 수 초 걸릴 수 있다 — 이슈 #81 계획 */
const TIMEOUT_MS = 15_000;

/** 프론트에만 있는 유일한 오류 코드. 문구도 프론트가 갖는 유일한 오류다 */
export const NETWORK_ERROR = 'NETWORK_ERROR';
const NETWORK_ERROR_MESSAGE = '서버에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요.';

const AUTH_TOKEN_EXPIRED = 'AUTH_TOKEN_EXPIRED';
const REISSUE_URL = '/auth/reissue';
/** 401이어도 재발급하지 않는 경로 — 로그인 · 가입 · 재발급 자신 */
const REISSUE_EXCLUDED_URLS: readonly string[] = ['/auth/login', '/auth/signup', REISSUE_URL];

// ── 오류 ────────────────────────────────────────────────────────────────

interface ApiErrorInit {
  /** 응답이 없으면 null */
  status: number | null;
  code: string;
  message: string;
  field?: string;
  retryAfter?: string;
}

export class ApiError extends Error {
  readonly status: number | null;
  readonly code: string;
  readonly field?: string;
  readonly retryAfter?: string;

  constructor(init: ApiErrorInit) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.field = init.field;
    this.retryAfter = init.retryAfter;
  }

  static fromEnvelope(body: ApiErrorBody, status: number): ApiError {
    return new ApiError({
      status,
      code: body.code,
      message: body.message,
      field: body.field,
      retryAfter: body.retryAfter,
    });
  }

  /** 봉투를 해석할 수 없는 실패 — 응답 없음(네트워크 · 타임아웃)과 봉투 아닌 응답(프록시 오류 페이지 등) */
  static network(status: number | null): ApiError {
    return new ApiError({ status, code: NETWORK_ERROR, message: NETWORK_ERROR_MESSAGE });
  }

  /** 어떤 실패든 ApiError로 바꾼다. 요청 헤더(토큰)는 담지 않는다 */
  static from(error: unknown): ApiError {
    if (error instanceof ApiError) return error;
    if (axios.isAxiosError(error) && error.response) {
      const body: unknown = error.response.data;
      if (isFailureEnvelope(body)) return ApiError.fromEnvelope(body.error, error.response.status);
      return ApiError.network(error.response.status);
    }
    return ApiError.network(null);
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isFailureEnvelope(value: unknown): value is { success: false; error: ApiErrorBody } {
  return (
    isObject(value) &&
    value.success === false &&
    isObject(value.error) &&
    typeof value.error.code === 'string' &&
    typeof value.error.message === 'string'
  );
}

function isEnvelope(value: unknown): value is ApiResponse<unknown> {
  return isObject(value) && (value.success === true || isFailureEnvelope(value));
}

// ── 인스턴스 ─────────────────────────────────────────────────────────────

const instance = axios.create({
  baseURL: BASE_URL,
  timeout: TIMEOUT_MS,
  // 배열 파라미터를 같은 키 반복으로 — riskGrade=CAUTION&riskGrade=SAFE. axios 기본값은 riskGrade[]=…
  paramsSerializer: { indexes: null },
});

/** 재발급 전용. 인터셉터를 거치지 않는다 — 401 재발급이 재발급 호출 자신에 다시 걸리지 않게 */
const reissueInstance = axios.create({ baseURL: BASE_URL, timeout: TIMEOUT_MS });

/** 배열 값만 사전순으로 정렬한 새 객체. 같은 필터가 같은 URL이 되게 한다 */
function sortArrayParams(params: Record<string, unknown>): Record<string, unknown> {
  const sorted: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(params)) {
    sorted[key] = Array.isArray(value) ? [...value].sort() : value;
  }
  return sorted;
}

function bearer(token: string): string {
  return `Bearer ${token}`;
}

instance.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) config.headers.set('Authorization', bearer(token));
  if (isObject(config.params) && !(config.params instanceof URLSearchParams)) {
    config.params = sortArrayParams(config.params);
  }
  return config;
});

function pathOf(url: string | undefined): string {
  return (url ?? '').split('?')[0] ?? '';
}

function canReissue(config: InternalAxiosRequestConfig | undefined): config is InternalAxiosRequestConfig {
  return (
    config !== undefined &&
    config.isRetryAfterReissue !== true &&
    !REISSUE_EXCLUDED_URLS.includes(pathOf(config.url))
  );
}

instance.interceptors.response.use(undefined, async (error: unknown) => {
  const apiError = ApiError.from(error);
  const config = axios.isAxiosError(error) ? error.config : undefined;

  if (apiError.status === 401 && apiError.code === AUTH_TOKEN_EXPIRED && canReissue(config)) {
    const sentAuthorization = config.headers.get('Authorization');
    const currentToken = getAccessToken();
    // 이 요청이 나간 뒤 다른 요청의 재발급이 이미 끝났으면 새 토큰으로 재시도만 한다
    const isAlreadyReissued = currentToken !== null && sentAuthorization !== bearer(currentToken);
    const isReissued = isAlreadyReissued || (await reissue());
    if (!isReissued) throw apiError;
    return instance.request({ ...config, isRetryAfterReissue: true });
  }
  throw apiError;
});

// ── 재발급 ──────────────────────────────────────────────────────────────

/** 로그인 · 소셜 인증 · 재발급 공통 응답 — 회원·인증 API 명세. 로그인 슬라이스의 api/user.ts가 import type으로 쓴다 */
export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  isNewUser: boolean;
}

/** 진행 중인 재발급. 동시에 난 401들이 이것 하나를 기다린다 */
let pendingReissue: Promise<boolean> | null = null;

/**
 * 리프레시 토큰으로 토큰을 재발급해 세션에 넣는다 (POST /api/auth/reissue).
 * 진행 중인 재발급이 있으면 그것을 기다린다. 실패하면 세션을 비우고 false —
 * 로그인 화면 이동은 router의 가드가 세션 없음을 보고 한다. 기동 시 세션 복원(main.tsx)도 이것을 쓴다.
 */
export function reissue(): Promise<boolean> {
  pendingReissue ??= runReissue().finally(() => {
    pendingReissue = null;
  });
  return pendingReissue;
}

async function runReissue(): Promise<boolean> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    clearSession();
    return false;
  }
  try {
    const response = await reissueInstance.post<unknown>(REISSUE_URL, { refreshToken });
    const body = response.data;
    if (!isObject(body) || body.success !== true || !isObject(body.data)) {
      clearSession();
      return false;
    }
    const { accessToken, refreshToken: nextRefreshToken } = body.data;
    if (typeof accessToken !== 'string' || typeof nextRefreshToken !== 'string') {
      clearSession();
      return false;
    }
    setTokens({ accessToken, refreshToken: nextRefreshToken });
    return true;
  } catch {
    clearSession();
    return false;
  }
}

// ── 요청 ────────────────────────────────────────────────────────────────

/**
 * 봉투를 벗긴다. 204이거나 본문이 비어 있으면 null — 호출하는 쪽은 T를 null로 둔다.
 * success:false면 상태가 200이어도 ApiError를 던진다 — 공통 규약은 200 + success:false(안내)를 허용한다.
 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  let response;
  try {
    response = await instance.request<unknown>(config);
  } catch (error) {
    throw ApiError.from(error);
  }
  const body = response.data;
  // axios는 빈 본문을 ''로 준다
  if (response.status === 204 || body === '' || body === null || body === undefined) return null as T;
  if (!isEnvelope(body)) throw ApiError.network(response.status);
  if (!body.success) throw ApiError.fromEnvelope(body.error, response.status);
  return body.data as T;
}
