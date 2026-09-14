import { createBrowserRouter } from 'react-router';
import HomePage from '../pages/HomePage';
import { AppShell } from './AppShell';
import { RequireAuth } from './RequireAuth';

// 라우트 표. 가드는 RequireAuth — Fast Refresh 규칙상 컴포넌트를 이 파일과 나눴다
export const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      // 첫 화면(지도)만 정적 import. 나머지 페이지는 lazy로 나눈다
      { path: '/', element: <HomePage /> },
      // /login · /signup — USER-02 · USER-01 슬라이스가 추가한다 (공개)
      {
        element: <RequireAuth />,
        children: [
          // /me/profile · /me/wishlist · /notifications · /me/notification-subscriptions — 각 슬라이스가 추가한다
        ],
      },
    ],
  },
]);
