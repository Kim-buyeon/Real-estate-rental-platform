// SDK가 없는 환경의 동작만 검증한다 — 렌더링 전반은 대상이 아니다 (docs/architecture/testing.md 1.1).
// jsdom에는 window.kakao가 없으므로 SDK를 가짜로 만들지 않고 그 상태를 그대로 쓴다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { propertyHandlers } from '../test/msw/handlers/property';
import { server } from '../test/msw/server';
import HomePage from './HomePage';

function renderHomePage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <HomePage />
    </QueryClientProvider>,
  );
}

describe('HomePage', () => {
  it('카카오맵 SDK가 없으면 자치구 집계가 도착해도 죽지 않고 안내를 보여준다', async () => {
    server.use(...propertyHandlers);

    renderHomePage();

    // 집계 응답이 도착하면 자치구 중심 좌표 조회로 이어지는데, SDK가 없으면 그 경로를 타지 않아야 한다
    await waitFor(() =>
      expect(screen.getByText('지도를 불러오지 못했습니다. 새로고침해 주세요.')).toBeInTheDocument(),
    );
    expect(screen.queryByText('강서구')).not.toBeInTheDocument();
  });
});
