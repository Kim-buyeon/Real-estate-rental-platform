// SDK가 없는 환경의 동작만 검증한다 — 렌더링 전반은 대상이 아니다 (docs/architecture/testing.md 1.1).
// jsdom에는 window.kakao가 없으므로 SDK를 가짜로 만들지 않고 그 상태를 그대로 쓴다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PROPERTY_DETAIL, PROPERTY_LIST_PAGE_1, propertyHandlers } from '../test/msw/handlers/property';
import { riskHandlers } from '../test/msw/handlers/risk';
import { server } from '../test/msw/server';
import HomePage from './HomePage';

const LIST_ITEM = PROPERTY_LIST_PAGE_1.items[0]!;

/** 요청 URL을 모으는 관측기 — PropertyDetailPanel.test.tsx와 같은 방식이다 */
function trackRequestedUrls() {
  const urls: string[] = [];
  server.events.on('request:start', ({ request }) => urls.push(request.url));
  return {
    urls,
    listUrls: () => urls.filter((url) => new URL(url).pathname === '/api/properties'),
    stop: () => server.events.removeAllListeners('request:start'),
  };
}

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
    // 자치구 선택기의 option이 아니라 지도 오버레이만 본다 — 오버레이는 건수를 읽는 버튼이다
    expect(screen.queryByRole('button', { name: /강서구 매물/ })).not.toBeInTheDocument();
  });

  it('목록이 기본 탭이고 상세 탭은 매물이 선택되기 전에는 비활성이며, 필터를 바꾸면 목록 요청에도 반영된다', async () => {
    server.use(...propertyHandlers);
    const tracker = trackRequestedUrls();

    renderHomePage();

    // 기본 탭은 목록 — 목록 항목이 렌더된다
    await waitFor(() => expect(screen.getByText(LIST_ITEM.address)).toBeInTheDocument());
    expect(screen.getByRole('tab', { name: '목록' })).toHaveAttribute('aria-selected', 'true');
    // 매물을 아직 고르지 않아 상세 탭은 비활성이다
    expect(screen.getByRole('tab', { name: '상세' })).toBeDisabled();

    // 첫 목록 요청에는 필터 조건이 없다
    const [firstListUrl] = tracker.listUrls();
    expect(firstListUrl).toBeDefined();
    expect(new URL(firstListUrl!).searchParams.has('contractType')).toBe(false);

    // 지도와 같은 filter 상태를 목록도 본다(매물 API 명세 1.1) — 필터를 바꾸면 목록 요청도 바뀐다
    fireEvent.change(screen.getByLabelText('계약유형'), { target: { value: 'DEPOSIT_ONLY' } });

    await waitFor(() => {
      const urls = tracker.listUrls();
      const lastUrl = urls[urls.length - 1];
      expect(lastUrl).toBeDefined();
      expect(new URL(lastUrl!).searchParams.get('contractType')).toBe('DEPOSIT_ONLY');
    });

    tracker.stop();
  });

  it('목록 항목을 고르면 상세 탭이 활성이 되고 그 매물의 상세가 열린다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);

    renderHomePage();

    await waitFor(() => expect(screen.getByText(LIST_ITEM.address)).toBeInTheDocument());
    // 목록의 첫 항목(propertyId 1024)이 매물 상세(PROPERTY_DETAIL, 같은 propertyId)와 같은 매물이다
    expect(LIST_ITEM.propertyId).toBe(PROPERTY_DETAIL.propertyId);

    fireEvent.click(screen.getByRole('button', { name: `${LIST_ITEM.address} 상세 보기` }));

    await waitFor(() => expect(screen.getByRole('tab', { name: '상세' })).toHaveAttribute('aria-selected', 'true'));
    // 상세 패널이 그 매물로 열렸다 — 패널에만 있는 「상세 닫기」 버튼으로 확인한다
    await waitFor(() => expect(screen.getByRole('button', { name: '상세 닫기' })).toBeInTheDocument());
  });
});
