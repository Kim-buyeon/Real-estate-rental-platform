// 메뉴 묶음(관심 목록 · 내 정보)의 머리 — 페이지 제목 · 탭 이동 · 선택 표시 · 헤더 메뉴의 활성(라우트 handle).
// 라우트 표를 그대로 메모리 라우터에 얹는다 — 어느 라우트가 어느 묶음인지는 handle 이라 표를 다시 적으면 어긋난 채 통과한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { setTokens } from '../session/store';
import { notificationHandlers, subscriptionHandlers } from '../test/msw/handlers/notification';
import { wishlistHandlers } from '../test/msw/handlers/property';
import { userHandlers } from '../test/msw/handlers/user';
import { server } from '../test/msw/server';
import { routes } from './router';

function renderAt(path: string) {
  setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
  server.use(...wishlistHandlers, ...notificationHandlers, ...subscriptionHandlers, ...userHandlers);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const mainMenu = () => screen.getByRole('navigation', { name: '주요 메뉴' });

describe('SectionLayout 관심 목록 묶음', () => {
  it('페이지 제목 「관심 목록」과 탭 「관심 매물 · 알림」이 뜨고 지금 위치의 탭이 aria-current 다', async () => {
    renderAt('/me/wishlist');

    expect(await screen.findByRole('heading', { level: 1, name: '관심 목록' })).toBeInTheDocument();
    const tabs = screen.getByRole('navigation', { name: '관심 목록' });
    expect(within(tabs).getAllByRole('link').map((link) => link.textContent)).toEqual(['관심 매물', '알림']);
    expect(within(tabs).getByRole('link', { name: '관심 매물' })).toHaveAttribute('aria-current', 'page');
    expect(within(tabs).getByRole('link', { name: '알림' })).not.toHaveAttribute('aria-current');
  });

  it('「알림」 탭을 누르면 /notifications 로 이동하고 선택 표시와 화면이 바뀐다', async () => {
    const router = renderAt('/me/wishlist');
    const tabs = await screen.findByRole('navigation', { name: '관심 목록' });

    fireEvent.click(within(tabs).getByRole('link', { name: '알림' }));

    expect(await screen.findByRole('region', { name: '알림' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/notifications');
    expect(within(tabs).getByRole('link', { name: '알림' })).toHaveAttribute('aria-current', 'page');
    expect(within(tabs).getByRole('link', { name: '관심 매물' })).not.toHaveAttribute('aria-current');
    // 제목은 탭을 옮겨도 하나다
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('각 페이지는 자기 h1 을 두지 않는다 — 영역 이름은 탭 이름(aria-label)이다', async () => {
    renderAt('/me/wishlist');

    expect(await screen.findByRole('region', { name: '관심 매물' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });
});

describe('SectionLayout 내 정보 묶음', () => {
  it('페이지 제목 「내 정보」와 탭 「계정 · 알림 설정」이 뜬다', async () => {
    renderAt('/me/profile');

    expect(await screen.findByRole('heading', { level: 1, name: '내 정보' })).toBeInTheDocument();
    const tabs = screen.getByRole('navigation', { name: '내 정보' });
    expect(within(tabs).getAllByRole('link').map((link) => link.textContent)).toEqual(['계정', '알림 설정']);
    expect(within(tabs).getByRole('link', { name: '계정' })).toHaveAttribute('aria-current', 'page');
  });

  it('「알림 설정」 탭을 누르면 /me/notification-subscriptions 로 이동한다', async () => {
    const router = renderAt('/me/profile');
    const tabs = await screen.findByRole('navigation', { name: '내 정보' });

    fireEvent.click(within(tabs).getByRole('link', { name: '알림 설정' }));

    expect(await screen.findByRole('region', { name: '알림 설정' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/me/notification-subscriptions');
    expect(within(tabs).getByRole('link', { name: '알림 설정' })).toHaveAttribute('aria-current', 'page');
  });
});

describe('헤더 메뉴의 활성은 라우트 handle 이 정한다', () => {
  it.each([
    ['/me/wishlist', '관심 목록'],
    ['/notifications', '관심 목록'],
    ['/me/profile', '내 정보'],
    ['/me/notification-subscriptions', '내 정보'],
  ])('%s 에서는 「%s」 메뉴만 활성이다', async (path, activeName) => {
    renderAt(path);
    await screen.findByRole('heading', { level: 1, name: activeName });

    const links = within(mainMenu()).getAllByRole('link');
    const active = links.filter((link) => link.getAttribute('aria-current') === 'true');
    expect(active).toHaveLength(1);
    expect(active[0]).toHaveTextContent(activeName);
  });
});

describe('SectionLayout 제목 역할 클래스', () => {
  it('h1 에 원 역할과 모바일 역할 클래스가 함께 붙는다', async () => {
    renderAt('/me/wishlist');

    const h1 = await screen.findByRole('heading', { level: 1 });
    expect(h1).toHaveClass('type-display', 'type-display-mobile');
  });
});
