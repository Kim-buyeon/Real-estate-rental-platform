// 이 테스트가 보는 것은 헤더 「로그인」 링크의 redirect 하나다 (이슈 140) — 헤더 렌더링 전반은 대상이
// 아니다 (테스트 전략 문서 1.1). 라우트 표를 그대로 메모리 라우터에 얹는다 — 로그인 · 가입 화면을
// 가리는 것이 라우트 표의 handle이라 표를 테스트가 다시 적으면 어긋난 채로 통과한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { propertyDetailPath } from '../lib/routes';
import { setTokens } from '../session/store';
import { NOTIFICATION_PAGE_1, notificationHandlers } from '../test/msw/handlers/notification';
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

describe('AppShell 상단 메뉴', () => {
  function menuLinks() {
    return within(screen.getByRole('navigation', { name: '주요 메뉴' })).getAllByRole('link');
  }

  it('비로그인이면 메뉴는 「지도」 하나다', async () => {
    renderAt('/login');
    await screen.findByRole('heading', { name: '로그인' });

    expect(menuLinks().map((link) => link.textContent)).toEqual(['지도']);
  });

  it('로그인하면 메뉴가 「지도 · 관심 목록 · 내 정보」 셋이고 관심 · 내 정보는 묶음의 첫 탭을 가리킨다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    server.use(...propertyHandlers, ...notificationHandlers);
    renderAt('/');
    await screen.findByRole('link', { name: /관심 목록/ });

    const links = menuLinks();
    expect(links.map((link) => link.textContent?.replace(/\d+$/, ''))).toEqual(['지도', '관심 목록', '내 정보']);
    expect(links[1]).toHaveAttribute('href', '/me/wishlist');
    expect(links[2]).toHaveAttribute('href', '/me/profile');
  });

  it('읽지 않은 알림 배지는 「관심 목록」 메뉴에 붙고 「내 정보」에는 없다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    server.use(...propertyHandlers, ...notificationHandlers);
    renderAt('/');

    const badge = await screen.findByLabelText(`읽지 않은 알림 ${NOTIFICATION_PAGE_1.unreadCount}건`);
    const [, favorites, myInfo] = menuLinks();
    expect(favorites).toContainElement(badge);
    expect(myInfo).not.toContainElement(badge);
  });

  it('읽지 않은 알림이 0이면 배지가 없다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    server.use(
      ...propertyHandlers,
      http.get('/api/notifications', () =>
        HttpResponse.json({ success: true, data: { ...NOTIFICATION_PAGE_1, unreadCount: 0 } }),
      ),
    );
    // 알림 조회 응답이 나간 뒤에 단언한다 — 응답 전에는 배지가 없는 것이 당연해 아무것도 증명하지 못한다
    let notificationResponses = 0;
    const onResponse = ({ request }: { request: Request }) => {
      if (new URL(request.url).pathname === '/api/notifications') notificationResponses += 1;
    };
    server.events.on('response:mocked', onResponse);
    renderAt('/');
    await screen.findByRole('link', { name: /관심 목록/ });
    await waitFor(() => expect(menuLinks()).toHaveLength(3));
    await waitFor(() => expect(notificationResponses).toBeGreaterThanOrEqual(1));
    server.events.removeListener('response:mocked', onResponse);
    // 응답이 화면에 반영될 한 틱을 준다
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(screen.queryByLabelText(/읽지 않은 알림/)).not.toBeInTheDocument();
  });

  it('묶음에 속하지 않은 화면(메인)에서는 어느 메뉴도 aria-current 가 아니다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    server.use(...propertyHandlers, ...notificationHandlers);
    renderAt('/');
    await screen.findByRole('link', { name: /관심 목록/ });

    expect(menuLinks().filter((link) => link.getAttribute('aria-current') === 'true')).toHaveLength(0);
  });
});
