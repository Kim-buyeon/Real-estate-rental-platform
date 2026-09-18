// MainPage 검증 — 렌더링 전반은 대상이 아니다(docs/architecture/testing.md 1.1). 여기서는 동작과
// 로직만 본다: 최근 등록 매물이 조건 없는 기존 목록 쿼리를 그대로 쓰고 앞 4건만 보여주는지(RecentProperties가
// 소유한 로직이지만 화면 조합 결과로 검증한다), 「지도에서 매물 찾기」·「전체 보기」가 갈린 경로(/map)를
// 가리키는지, 빈 목록·조회 실패가 EmptyState·Alert로 갈리는지다. 위험도 3단계 카드(RiskGradeGuide)는
// 고정 문구 · 배지라 로직이 없어 단언하지 않는다 — 등급 배지의 색·문구가 domain/risk.ts에서 온다는 것은
// PropertyList.test.tsx가 이미 지킨다(같은 도메인 함수를 쓰므로 중복이다).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { PropertyListItem } from '../api/property';
import type { CursorPage } from '../api/types';
import { propertyDetailPath } from '../lib/routes';
import { server } from '../test/msw/server';
import MainPage from './MainPage';

const MAP_PATH = '/map';

/** 명세 1.6 예시 항목 하나의 필드 형태 그대로. propertyId · address만 바꿔 5건을 만든다 */
function listItem(propertyId: number): PropertyListItem {
  return {
    propertyId,
    district: '강서구',
    address: `서울특별시 강서구 화곡로 ${propertyId}`,
    propertyType: 'APARTMENT',
    contractType: 'DEPOSIT_ONLY',
    deposit: 230000000,
    monthlyRent: 0,
    areaSqm: 42.5,
    floor: 3,
    riskGrade: 'SAFE',
    debtRatio: 68.0,
    registeredAt: '2026-07-20T14:03:00+09:00',
  };
}

const FIVE_ITEMS: PropertyListItem[] = [1, 2, 3, 4, 5].map(listItem);

function renderMainPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <MainPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

/** 요청 URL을 모으는 관측기 — PropertyList.test.tsx와 같은 방식이다 */
function trackRequestedUrls() {
  const urls: string[] = [];
  server.events.on('request:start', ({ request }) => urls.push(request.url));
  return {
    urls,
    listUrls: () => urls.filter((url) => new URL(url).pathname === '/api/properties'),
    stop: () => server.events.removeAllListeners('request:start'),
  };
}

function mockPropertyList(page: CursorPage<PropertyListItem>) {
  server.use(http.get('/api/properties', () => HttpResponse.json({ success: true, data: page })));
}

describe('MainPage', () => {
  it('최근 등록 매물이 조건 없이 기존 목록 조회를 부르고 앞 4건만 보여준다', async () => {
    mockPropertyList({ items: FIVE_ITEMS, nextCursor: null, hasNext: false });
    const tracker = trackRequestedUrls();

    renderMainPage();

    await waitFor(() => expect(screen.getByText(FIVE_ITEMS[0]!.address)).toBeInTheDocument());

    // 앞 4건만 — 5번째는 없다
    expect(screen.getByText(FIVE_ITEMS[1]!.address)).toBeInTheDocument();
    expect(screen.getByText(FIVE_ITEMS[2]!.address)).toBeInTheDocument();
    expect(screen.getByText(FIVE_ITEMS[3]!.address)).toBeInTheDocument();
    expect(screen.queryByText(FIVE_ITEMS[4]!.address)).not.toBeInTheDocument();

    // 조건 없이 부른다 — 필터 파라미터가 없다(매물 목록의 기존 쿼리를 그대로 쓴다)
    const [requestUrl] = tracker.listUrls();
    expect(requestUrl).toBeDefined();
    expect(new URL(requestUrl!).search).toBe('');

    tracker.stop();
  });

  it('최근 등록 매물 카드가 그 매물의 지도 딥링크(/map?propertyId=)를 가리킨다', async () => {
    mockPropertyList({ items: FIVE_ITEMS, nextCursor: null, hasNext: false });

    renderMainPage();

    await waitFor(() => expect(screen.getByText(FIVE_ITEMS[0]!.address)).toBeInTheDocument());

    expect(screen.getByRole('link', { name: `${FIVE_ITEMS[0]!.address} 상세 보기` })).toHaveAttribute(
      'href',
      propertyDetailPath(FIVE_ITEMS[0]!.propertyId),
    );
  });

  it('「지도에서 매물 찾기」와 「전체 보기」가 둘 다 /map을 가리킨다', () => {
    mockPropertyList({ items: FIVE_ITEMS, nextCursor: null, hasNext: false });

    renderMainPage();

    expect(screen.getByRole('link', { name: '지도에서 매물 찾기' })).toHaveAttribute('href', MAP_PATH);
    expect(screen.getByRole('link', { name: '전체 보기' })).toHaveAttribute('href', MAP_PATH);
  });

  it('최근 등록 매물이 없으면 EmptyState 안내를 보여준다', async () => {
    mockPropertyList({ items: [], nextCursor: null, hasNext: false });

    renderMainPage();

    expect(await screen.findByText('아직 등록된 매물이 없습니다.')).toBeInTheDocument();
  });

  it('최근 등록 매물 조회가 실패하면 Alert로 서버 오류 문구를 보여준다', async () => {
    const message = '일시적인 오류로 매물을 불러오지 못했습니다.';
    server.use(
      http.get('/api/properties', () =>
        HttpResponse.json({ success: false, error: { code: 'INTERNAL_ERROR', message } }, { status: 500 }),
      ),
    );

    renderMainPage();

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
  });
});
