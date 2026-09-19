// 이 테스트가 보는 것은 헤더 「로그인」 링크의 redirect 하나다 (이슈 140) — 헤더 렌더링 전반은 대상이
// 아니다 (테스트 전략 문서 1.1). 라우트 표를 그대로 메모리 라우터에 얹는다 — 로그인 · 가입 화면을
// 가리는 것이 라우트 표의 handle이라 표를 테스트가 다시 적으면 어긋난 채로 통과한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { propertyDetailPath } from '../lib/routes';
import { PROPERTY_DETAIL, propertyHandlers } from '../test/msw/handlers/property';
import { riskHandlers } from '../test/msw/handlers/risk';
import { server } from '../test/msw/server';
import { routes } from './router';

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

/** 헤더의 로그인 링크. 로그인 화면의 폼 버튼과 겹치지 않게 헤더(banner) 안에서 찾는다 */
function headerLoginLink() {
  return within(screen.getByRole('banner')).getByRole('link', { name: '로그인' });
}

describe('AppShell 헤더 로그인 링크', () => {
  it('지금 위치(경로 + 검색 파라미터)를 redirect로 싣는다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);
    const path = propertyDetailPath(PROPERTY_DETAIL.propertyId);

    renderAt(path);

    // /map은 lazy 라우트라 화면이 뜰 때까지 기다린다 — 상세 패널의 닫기 버튼으로 확인한다
    await screen.findByRole('button', { name: '상세 닫기' });
    // RequireAuth와 같은 형식이다 — LoginPage가 받아 그 경로로 돌려보낸다
    expect(headerLoginLink()).toHaveAttribute('href', `/login?redirect=${encodeURIComponent(path)}`);
  });

  it('로그인 화면에서는 redirect를 싣지 않는다 — 로그인 뒤 다시 로그인으로 오는 루프를 막는다', async () => {
    renderAt('/login');

    await screen.findByRole('heading', { name: '로그인' });
    expect(headerLoginLink()).toHaveAttribute('href', '/login');
  });

  it('가입 화면에서도 redirect를 싣지 않는다', async () => {
    renderAt('/signup');

    await screen.findByText('이미 계정이 있으신가요?', { exact: false });
    expect(headerLoginLink()).toHaveAttribute('href', '/login');
  });
});
