import './styles/global.css';

import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router/dom';
import { reissue } from './api/client';
import { queryClient } from './app/queryClient';
import { router } from './app/router';
import { getRefreshToken } from './session/store';

async function bootstrap() {
  // 리프레시 토큰이 있으면 재발급을 먼저 한 번 한다 — 인증 선택 엔드포인트가 처음부터 개인화 결과를 받게.
  // 실패하면 reissue()가 세션을 비운다
  if (getRefreshToken()) await reissue();

  const container = document.getElementById('root');
  if (!container) throw new Error('#root 요소가 없습니다');

  createRoot(container).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </StrictMode>,
  );
}

void bootstrap();
