// 공통 규약 1.2 응답 봉투 · 1.4 커서 목록

/** 실패 응답의 error. field는 검증 대상 필드가 있을 때만, retryAfter는 429에서 정해진 시각이 있을 때만 온다 */
export interface ApiErrorBody {
  code: string;
  message: string;
  field?: string;
  retryAfter?: string;
}

export type ApiResponse<T> =
  | { success: true; data: T }
  | { success: false; error: ApiErrorBody };

export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
  hasNext: boolean;
}
