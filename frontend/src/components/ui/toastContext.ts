import { createContext } from 'react';
import type { ToastVariant } from './Toast';

/** 토스트가 떠 있는 시간. 위치 · 겹침 순서와 함께 정의서 K-19 가 「쓰는 화면에서 정한다」로 넘긴 값이다 */
export const TOAST_DURATION_MS = 2500;

export interface ToastOptions {
  variant?: ToastVariant;
}

export interface ToastApi {
  /** 문구 하나를 띄운다. 이미 떠 있으면 바꿔 끼우고 시간을 처음부터 다시 잰다 — 한 번에 한 장이다 */
  show: (message: string, options?: ToastOptions) => void;
}

/**
 * ToastProvider 와 useToast 가 함께 쓰는 컨텍스트. 컴포넌트 파일과 나눈 것은 Fast Refresh 규칙
 * (컴포넌트 파일은 컴포넌트만 내보낸다) 때문이다 — buttonClassName.ts 와 같은 이유다.
 */
export const ToastContext = createContext<ToastApi | null>(null);
