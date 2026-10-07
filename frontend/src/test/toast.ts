import { screen } from '@testing-library/react';

/**
 * 토스트 알림 영역 — ToastProvider 가 늘 붙여 두는 role=status · aria-live=polite · aria-atomic 자리다.
 * Spinner · Skeleton · Alert(info) 도 role=status 라서 getByRole('status') 만으로는 토스트를 특정할 수 없다.
 */
export function toastRegion(): HTMLElement {
  const region = screen
    .getAllByRole('status')
    .find((el) => el.getAttribute('aria-live') === 'polite' && el.getAttribute('aria-atomic') === 'true');
  if (!region) throw new Error('토스트 알림 영역(ToastProvider)이 렌더되지 않았다');
  return region;
}
