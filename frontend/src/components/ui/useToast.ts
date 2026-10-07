import { useContext } from 'react';
import { ToastContext, type ToastApi } from './toastContext';

/**
 * 토스트를 띄우는 훅. ToastProvider 안에서만 쓴다 — 밖에서 부르면 띄울 자리가 없으므로 조용히
 * 넘어가지 않고 오류를 낸다(띄웠는데 안 보이는 결함이 숨지 않게).
 */
export function useToast(): ToastApi {
  const api = useContext(ToastContext);
  if (!api) throw new Error('useToast 는 ToastProvider 안에서만 쓸 수 있습니다');
  return api;
}
