import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { clearSession } from '../session/store';
import { server } from './msw/server';

// 요청 핸들러는 테스트마다 server.use()로 붙인다. 등록되지 않은 요청은 실패로 드러낸다
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  // 액세스 토큰은 모듈 변수라 테스트 사이에 남는다 — 세션을 비워 격리한다
  clearSession();
  localStorage.clear();
});
afterAll(() => server.close());
