import { createBrowserRouter } from 'react-router';
import HomePage from '../pages/HomePage';
import { AppShell } from './AppShell';
import { RequireAuth } from './RequireAuth';

// 첫 화면(지도)만 정적 import. 나머지 페이지는 라우트 lazy로 나눈다 —
// React.lazy 컴포넌트를 이 파일에 두면 Fast Refresh 규칙(react-refresh/only-export-components)에 걸린다

// 라우트 표. 가드는 RequireAuth — Fast Refresh 규칙상 컴포넌트를 이 파일과 나눴다
export const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { path: '/', element: <HomePage /> },
      // 공개. 로그인 상태로 /login에 오면 LoginPage가 redirect 경로(없으면 /)로 보낸다
      { path: '/login', lazy: async () => ({ Component: (await import('../pages/LoginPage')).default }) },
      { path: '/signup', lazy: async () => ({ Component: (await import('../pages/SignupPage')).default }) },
      {
        element: <RequireAuth />,
        children: [
          // /me/profile · /me/wishlist · /notifications · /me/notification-subscriptions — 각 슬라이스가 추가한다
        ],
      },
    ],
  },
]);
