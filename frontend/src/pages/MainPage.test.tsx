// MainPage 검증 — 렌더링 전반은 대상이 아니다(docs/architecture/testing.md 1.1). 여기서는 동작과
// 로직만 본다: 최근 등록 매물이 조건 없는 기존 목록 쿼리를 그대로 쓰고 앞 4건만 보여주는지(RecentProperties가
// 소유한 로직이지만 화면 조합 결과로 검증한다), 본문에 /map 링크가 없는지(카드 딥링크 제외), 준비 중 입구 · 칩 필터가
// 동작하는지, 빈 목록·조회 실패가 EmptyState·Alert로 갈리는지다. 위험 등급 안내(RiskGradeBadges ·
// RiskCriteriaCards)는 고정 문구 · 배지라 로직이 없어 단언하지 않는다 — 등급 배지의 색·문구가 domain/risk.ts에서 온다는 것은
// PropertyList.test.tsx가 이미 지킨다(같은 도메인 함수를 쓰므로 중복이다).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { PropertyListItem } from '../api/property';
import type { CursorPage } from '../api/types';
import { COMING_SOON_MESSAGE, ToastProvider } from '../components/ui';
import { propertyDetailPath } from '../lib/routes';
import { server } from '../test/msw/server';
import { toastRegion } from '../test/toast';
import MainPage from './MainPage';

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
        <ToastProvider>
          <MainPage />
        </ToastProvider>
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

  it('본문에 /map 링크가 없다 — 매물 카드 딥링크(/map?propertyId=)는 예외다', async () => {
    mockPropertyList({ items: FIVE_ITEMS, nextCursor: null, hasNext: false });

    renderMainPage();

    await waitFor(() => expect(screen.getByText(FIVE_ITEMS[0]!.address)).toBeInTheDocument());

    const mapLinks = screen
      .getAllByRole('link')
      .filter((link) => new URL(link.getAttribute('href') ?? '', 'http://localhost').pathname === '/map');
    // 카드 딥링크는 있다(아래 단언이 빈 목록을 통과시키지 않도록)
    expect(mapLinks.length).toBeGreaterThan(0);
    for (const link of mapLinks) {
      expect(link.getAttribute('href')).toMatch(/^\/map\?propertyId=\d+$/);
    }
    expect(screen.queryByRole('link', { name: '지도에서 매물 찾기' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '전체 보기' })).not.toBeInTheDocument();
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

  it('구역 제목이 모두 있고 「참고 서비스」는 없다', () => {
    mockPropertyList({ items: [], nextCursor: null, hasNext: false });

    renderMainPage();

    expect(
      screen.getByRole('heading', { level: 1, name: /전세사기 위험 등급,\s*계약 전에 확인하세요/ }),
    ).toBeInTheDocument();
    for (const title of ['최근 등록 매물', '위험 등급 판정 기준', '전세 계약 가이드']) {
      expect(screen.getByRole('heading', { level: 2, name: title })).toBeInTheDocument();
    }
    expect(screen.queryByText('참고 서비스')).not.toBeInTheDocument();
  });

  describe('준비 중 입구는 이동하지 않고 알림만 띄운다', () => {
    function expectComingSoon() {
      expect(within(toastRegion()).getByText(COMING_SOON_MESSAGE)).toBeInTheDocument();
    }

    it('알림 영역은 누르기 전에는 비어 있다', () => {
      mockPropertyList({ items: [], nextCursor: null, hasNext: false });
      renderMainPage();

      expect(toastRegion()).toBeEmptyDOMElement();
    });

    it.each(['전월세 전환율 계산', '보증 신청기한 계산', '대출 상품 추천'])('카테고리 「%s」', (title) => {
      mockPropertyList({ items: [], nextCursor: null, hasNext: false });
      renderMainPage();

      fireEvent.click(screen.getByRole('button', { name: new RegExp(title) }));

      expectComingSoon();
    });

    it.each(['위험 등급 해설 상담', '상담 이력'])('상담 타일 「%s」', (name) => {
      mockPropertyList({ items: [], nextCursor: null, hasNext: false });
      renderMainPage();

      fireEvent.click(screen.getByRole('button', { name }));

      expectComingSoon();
    });

    it('가이드 카드', () => {
      mockPropertyList({ items: [], nextCursor: null, hasNext: false });
      renderMainPage();

      fireEvent.click(screen.getByRole('button', { name: /등기부등본 보는 법/ }));

      expectComingSoon();
    });

    it('검색 제출', () => {
      mockPropertyList({ items: [], nextCursor: null, hasNext: false });
      renderMainPage();

      fireEvent.click(screen.getByRole('button', { name: '검색' }));

      expectComingSoon();
    });
  });

  describe('칩을 누르면 목록 요청에 그 필터가 실린다', () => {
    const CHIP_LIST = '최근 등록 매물 조건';

    it.each([
      ['전세', 'contractType', 'DEPOSIT_ONLY'],
      ['월세', 'contractType', 'MONTHLY_RENT'],
      ['안전', 'riskGrade', 'SAFE'],
      ['아파트', 'propertyType', 'APARTMENT'],
      ['오피스텔', 'propertyType', 'OFFICETEL'],
    ])('「%s」 → %s=%s', async (label, key, value) => {
      mockPropertyList({ items: FIVE_ITEMS, nextCursor: null, hasNext: false });
      const tracker = trackRequestedUrls();
      renderMainPage();
      await waitFor(() => expect(tracker.listUrls()).toHaveLength(1));

      fireEvent.click(within(screen.getByRole('tablist', { name: CHIP_LIST })).getByRole('tab', { name: label }));

      await waitFor(() => expect(tracker.listUrls()).toHaveLength(2));
      const params = new URL(tracker.listUrls()[1]!).searchParams;
      expect(params.getAll(key)).toEqual([value]);
      expect([...params.keys()]).toEqual([key]);

      tracker.stop();
    });

    it('「전체」로 돌아오면 필터 없이 다시 부른다', async () => {
      mockPropertyList({ items: FIVE_ITEMS, nextCursor: null, hasNext: false });
      const tracker = trackRequestedUrls();
      renderMainPage();
      const tabs = within(screen.getByRole('tablist', { name: CHIP_LIST }));
      await waitFor(() => expect(tracker.listUrls()).toHaveLength(1));

      fireEvent.click(tabs.getByRole('tab', { name: '전세' }));
      await waitFor(() => expect(tracker.listUrls()).toHaveLength(2));
      fireEvent.click(tabs.getByRole('tab', { name: '전체' }));

      await waitFor(() => expect(tracker.listUrls().length).toBeGreaterThanOrEqual(3));
      expect(new URL(tracker.listUrls().at(-1)!).search).toBe('');

      tracker.stop();
    });
  });
});
