import { createBrowserRouter, type RouteObject } from 'react-router';
import MainPage from '../pages/MainPage';
import { AppShell } from './AppShell';
import { RequireAuth } from './RequireAuth';
import type { RouteHandle } from './routeHandle';

// 첫 화면(메인)만 정적 import. 나머지 페이지는 라우트 lazy로 나눈다 —
// React.lazy 컴포넌트를 이 파일에 두면 Fast Refresh 규칙(react-refresh/only-export-components)에 걸린다

// 라우트 표. 가드는 RequireAuth — Fast Refresh 규칙상 컴포넌트를 이 파일과 나눴다.
// 표를 따로 내보내는 것은 테스트가 같은 표를 메모리 라우터에 얹기 위함이다 — 라우트의 성질(handle)을
// 테스트가 다시 적으면 라우트 표와 어긋난다
export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      // 메인은 첫 화면이라 정적 import다. 푸터가 붙는다 — 데이터 출처 · 면책이 여기 있다
      { path: '/', element: <MainPage /> },
      // 지도는 뷰포트의 남은 높이를 전부 쓰는 유동 아키타입이라 푸터를 붙이지 않는다 (이슈 112).
      // 셸도 화면 높이에 고정한다 — 목록이 길어도 지도 높이가 늘지 않아 서울이 화면 가운데에 맞는다 (이슈 158)
      {
        path: '/map',
        lazy: async () => ({ Component: (await import('../pages/MapPage')).default }),
        handle: { hideFooter: true, fillsViewport: true } satisfies RouteHandle,
      },
      // 공개. 로그인 상태로 /login에 오면 LoginPage가 redirect 경로(없으면 /)로 보낸다
      // 로그인 · 가입은 헤더 로그인 링크가 redirect로 싣지 않는 화면이다 (이슈 140)
      {
        path: '/login',
        lazy: async () => ({ Component: (await import('../pages/LoginPage')).default }),
        handle: { isAuthEntry: true } satisfies RouteHandle,
      },
      {
        path: '/signup',
        lazy: async () => ({ Component: (await import('../pages/SignupPage')).default }),
        handle: { isAuthEntry: true } satisfies RouteHandle,
      },
      // 비밀번호 찾기 · 재설정(USER-06)도 로그인 전에 쓰는 인증 진입 화면이다 — 헤더 로그인 링크가 redirect로 싣지 않는다 (이슈 148)
      {
        path: '/password-reset',
        lazy: async () => ({ Component: (await import('../pages/PasswordResetRequestPage')).default }),
        handle: { isAuthEntry: true } satisfies RouteHandle,
      },
      {
        path: '/password-reset/confirm',
        lazy: async () => ({ Component: (await import('../pages/PasswordResetConfirmPage')).default }),
        handle: { isAuthEntry: true } satisfies RouteHandle,
      },
      {
        element: <RequireAuth />,
        children: [
          { path: '/me/profile', lazy: async () => ({ Component: (await import('../pages/ProfilePage')).default }) },
          { path: '/me/wishlist', lazy: async () => ({ Component: (await import('../pages/WishlistPage')).default }) },
          {
            path: '/notifications',
            lazy: async () => ({ Component: (await import('../pages/NotificationsPage')).default }),
          },
          {
            path: '/me/notification-subscriptions',
            lazy: async () => ({ Component: (await import('../pages/SubscriptionsPage')).default }),
          },
        ],
      },
    ],
  },
];

export const router = createBrowserRouter(routes);
