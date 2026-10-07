import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Toast, type ToastVariant } from './Toast';
import { TOAST_DURATION_MS, ToastContext, type ToastApi, type ToastOptions } from './toastContext';
import styles from './Toast.module.css';

interface CurrentToast {
  /** 띄울 때마다 바뀐다 — 같은 문구를 다시 띄워도 새 요소로 갈아 끼워 화면 낭독기가 다시 읽는다 */
  id: number;
  message: string;
  variant: ToastVariant;
}

/**
 * 앱 전체에 하나 있는 토스트 자리. 마운트는 조합 루트(app/AppShell)가 한 번 한다 —
 * 본문(라우트)과 푸터가 같은 자리를 쓴다.
 *
 * 한 번에 한 장이다. 새로 띄우면 앞의 것을 바꿔 끼우고 타이머를 다시 건다 — 쌓지 않는다.
 * 알림 영역(role="status" · aria-live="polite")은 비어 있어도 늘 붙어 있다: 영역이 내용과 함께 나타나면
 * 화면 낭독기가 첫 문구를 놓친다.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [current, setCurrent] = useState<CurrentToast | null>(null);
  const nextIdRef = useRef(0);
  const timerRef = useRef<number | undefined>(undefined);

  const show = useCallback((message: string, options?: ToastOptions) => {
    window.clearTimeout(timerRef.current);
    nextIdRef.current += 1;
    setCurrent({ id: nextIdRef.current, message, variant: options?.variant ?? 'default' });
    timerRef.current = window.setTimeout(() => setCurrent(null), TOAST_DURATION_MS);
  }, []);

  // 화면을 떠나면 남은 타이머를 지운다 — 언마운트 뒤 상태를 바꾸지 않는다
  useEffect(() => () => window.clearTimeout(timerRef.current), []);

  const api = useMemo<ToastApi>(() => ({ show }), [show]);

  return (
    <ToastContext value={api}>
      {children}
      <div className={styles.region} role="status" aria-live="polite" aria-atomic="true">
        {current && (
          <Toast key={current.id} variant={current.variant}>
            {current.message}
          </Toast>
        )}
      </div>
    </ToastContext>
  );
}
