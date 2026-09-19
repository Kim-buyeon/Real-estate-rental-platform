import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, vi } from 'vitest';
import { clearSession } from '../session/store';
import { server } from './msw/server';

// 테스트는 카카오맵 SDK를 내려받지 않는다. 로컬 .env에 키가 있어도 비워 두어 로더가 스크립트를 넣지 않고
// 곧바로 실패하게 한다 — jsdom은 외부 스크립트를 실행하지 않아 넣으면 로드가 끝나지 않는다.
// SDK가 필요한 테스트는 test/kakao.ts의 가짜를 설치한다(설치되어 있으면 로더가 키를 보지 않는다)
vi.stubEnv('VITE_KAKAO_MAP_KEY', '');

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
