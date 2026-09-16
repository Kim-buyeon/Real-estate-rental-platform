// HomePage 렌더 검증 — jsdom에는 window.kakao가 없다는 점을 그대로 이용한다.
// SDK를 가짜로 만들지 않고, 없는 상태에서 나오는 안내와 필터 바 노출을 확인한다.
// 근거: docs/architecture/testing.md 1.1(프론트 로직 테스트) · frontend/CLAUDE.md 지도.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
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
  it('카카오맵 SDK가 없으면 지도를 불러오지 못했다는 안내를 보여준다', () => {
    server.use(...propertyHandlers);

    renderHomePage();

    expect(screen.getByText('지도를 불러오지 못했습니다. 새로고침해 주세요.')).toBeInTheDocument();
  });

  it('지도가 없어도 필터 바는 보인다', () => {
    server.use(...propertyHandlers);

    renderHomePage();

    expect(screen.getByLabelText('계약유형')).toBeInTheDocument();
  });
});
