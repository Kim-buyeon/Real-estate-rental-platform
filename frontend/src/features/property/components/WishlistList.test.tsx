// WishlistList 검증 — 커서 더 불러오기(응답 nextCursor를 다음 요청 cursor에 그대로 실었는지 포함),
// 해제(DELETE와 목록 재조회), 항목별 처리 상태(훅 하나를 여러 항목이 나눠 쓰지만 누른 항목만
// 로딩), 등급 표시(riskGrade · previousGrade 조합과 null 처리)를 확인한다 (이슈 #96 계획 8번 ·
// 검증 표). 문구는 domain/risk.ts의 riskGradeLabel로 확인해 하드코딩하지 않는다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { riskGradeLabel } from '../../../domain/risk';
import { propertyDetailPath } from '../../../lib/routes';
import { WISHLIST_PAGE_1, WISHLIST_PAGE_2, wishlistHandlers } from '../../../test/msw/handlers/property';
import { server } from '../../../test/msw/server';
import { WishlistList } from './WishlistList';

// 항목의 「상세 보기」가 라우터 Link라 컨텍스트가 있어야 렌더된다(react-router 8) — MainPage.test.tsx와
// 같은 방식(MemoryRouter). 이 파일은 목록 자체의 경로 조립은 검증하지 않으므로 initialEntries는 두지 않는다
function renderList() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <WishlistList />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

/** 항목의 <li>를 propertyId로 특정한다 — 해제 버튼의 접근 가능한 이름이 항목마다 같다 */
function findListItem(propertyId: number) {
  const item = WISHLIST_PAGE_1.items.find((i) => i.propertyId === propertyId) ??
    WISHLIST_PAGE_2.items.find((i) => i.propertyId === propertyId);
  if (!item) throw new Error(`fixture에 propertyId ${propertyId} 항목이 없다`);
  return screen.getByText(item.district).closest('li')!;
}

describe('WishlistList', () => {
  it('첫 쪽이 보이고, 더 보기를 누르면 둘째 쪽이 이어 붙고, 마지막 쪽에서는 더 보기가 사라진다', async () => {
    const requestedCursors: (string | null)[] = [];
    server.use(...wishlistHandlers);
    const onRequestStart = ({ request }: { request: Request }) => {
      if (request.method === 'GET' && request.url.includes('/me/wishlist')) {
        requestedCursors.push(new URL(request.url).searchParams.get('cursor'));
      }
    };
    server.events.on('request:start', onRequestStart);

    renderList();

    // 첫 쪽 항목이 보인다
    await waitFor(() => expect(screen.getByText(WISHLIST_PAGE_1.items[0]!.district)).toBeInTheDocument());
    expect(screen.getByText(WISHLIST_PAGE_1.items[1]!.district)).toBeInTheDocument();
    // 둘째 쪽 항목은 아직 없다
    expect(screen.queryByText(WISHLIST_PAGE_2.items[0]!.district)).not.toBeInTheDocument();

    // 첫 요청은 커서 없이 나간다
    expect(requestedCursors[0]).toBeNull();

    const moreButton = screen.getByRole('button', { name: '더 보기' });
    fireEvent.click(moreButton);

    // 둘째 쪽 항목이 이어 붙는다 — 첫 쪽 항목은 그대로 남는다
    await waitFor(() => expect(screen.getByText(WISHLIST_PAGE_2.items[0]!.district)).toBeInTheDocument());
    expect(screen.getByText(WISHLIST_PAGE_1.items[0]!.district)).toBeInTheDocument();

    // 두 번째 요청의 cursor는 첫 쪽 응답의 nextCursor 그대로다 (공통 규약 1.4)
    expect(requestedCursors[1]).toBe(WISHLIST_PAGE_1.nextCursor);

    // 마지막 쪽(hasNext: false)이므로 더 보기가 사라진다
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument();

    server.events.removeListener('request:start', onRequestStart);
  });

  it('항목의 관심 해제를 누르면 그 매물로 DELETE가 나가고 목록이 다시 조회된다', async () => {
    server.use(...wishlistHandlers);

    let getCount = 0;
    let deleteRequest: { url: string; method: string } | null = null;
    const onRequestStart = ({ request }: { request: Request }) => {
      if (request.method === 'GET' && request.url.includes('/me/wishlist')) getCount += 1;
      if (request.method === 'DELETE' && request.url.includes('/me/wishlist/')) {
        deleteRequest = { url: request.url, method: request.method };
      }
    };
    server.events.on('request:start', onRequestStart);

    renderList();

    const target = WISHLIST_PAGE_1.items[0]!;
    await waitFor(() => expect(screen.getByText(target.district)).toBeInTheDocument());
    await waitFor(() => expect(getCount).toBe(1));

    const item = findListItem(target.propertyId);
    fireEvent.click(within(item).getByRole('button', { name: '관심 해제' }));

    await waitFor(() => expect(deleteRequest).not.toBeNull());
    expect(deleteRequest!.url).toContain(`/me/wishlist/${target.propertyId}`);

    // 해제 성공의 무효화로 목록이 다시 조회된다 — 요청 수가 는다
    await waitFor(() => expect(getCount).toBe(2));

    server.events.removeListener('request:start', onRequestStart);
  });

  it('훅 하나를 목록이 나눠 쓰지만, 누른 항목의 해제 버튼만 로딩되고 다른 항목은 그대로다', async () => {
    // GET · POST는 그대로 두고 DELETE만 늦춰 로딩 상태를 관측할 시간을 번다.
    // 구현이 removeMutation.variables === item.propertyId로 가르지 않고 훅의 isPending 하나로만
    // 판단하면 두 버튼이 함께 로딩되어 이 테스트가 실패한다.
    // 먼저 나온 핸들러가 이긴다(MSW) — 늦추는 핸들러를 wishlistHandlers보다 앞에 둔다
    server.use(
      http.delete('/api/me/wishlist/:propertyId', async () => {
        await delay(50);
        return new HttpResponse(null, { status: 204 });
      }),
      ...wishlistHandlers,
    );

    renderList();

    const clicked = WISHLIST_PAGE_1.items[0]!;
    const untouched = WISHLIST_PAGE_1.items[1]!;
    await waitFor(() => expect(screen.getByText(clicked.district)).toBeInTheDocument());

    const clickedItem = findListItem(clicked.propertyId);
    const untouchedItem = findListItem(untouched.propertyId);

    fireEvent.click(within(clickedItem).getByRole('button', { name: '관심 해제' }));

    // 누른 항목의 버튼만 로딩(aria-busy=true)이고, 손대지 않은 항목은 로딩이 아니다
    await waitFor(() =>
      expect(within(clickedItem).getByRole('button', { name: '관심 해제' })).toHaveAttribute('aria-busy', 'true'),
    );
    expect(within(untouchedItem).getByRole('button', { name: '관심 해제' })).toHaveAttribute('aria-busy', 'false');

    // 해제가 끝날 때까지 기다려 다음 테스트에 영향이 남지 않게 한다
    await waitFor(
      () =>
        expect(
          within(clickedItem).queryByRole('button', { name: '관심 해제' })?.getAttribute('aria-busy'),
        ).not.toBe('true'),
      { timeout: 3000 },
    );
  });

  it('riskGrade · previousGrade가 함께 보이고, 둘 중 하나가 null인 항목도 깨지지 않는다', async () => {
    server.use(...wishlistHandlers);

    renderList();

    const normal = WISHLIST_PAGE_1.items[0]!; // riskGrade · previousGrade 모두 있음
    const firstAnalysis = WISHLIST_PAGE_1.items[1]!; // previousGrade만 null

    await waitFor(() => expect(screen.getByText(normal.district)).toBeInTheDocument());

    const normalItem = findListItem(normal.propertyId);
    expect(within(normalItem).getByText(riskGradeLabel(normal.riskGrade))).toBeInTheDocument();
    expect(within(normalItem).getByText(riskGradeLabel(normal.previousGrade))).toBeInTheDocument();

    const firstAnalysisItem = findListItem(firstAnalysis.propertyId);
    expect(within(firstAnalysisItem).getByText(riskGradeLabel(firstAnalysis.riskGrade))).toBeInTheDocument();
    expect(within(firstAnalysisItem).getByText(riskGradeLabel(firstAnalysis.previousGrade))).toBeInTheDocument();

    // 둘째 쪽(riskGrade · previousGrade 모두 null — 미분석)까지 불러온다
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    const unanalyzed = WISHLIST_PAGE_2.items[0]!;
    await waitFor(() => expect(screen.getByText(unanalyzed.district)).toBeInTheDocument());

    const unanalyzedItem = findListItem(unanalyzed.propertyId);
    // riskGrade와 previousGrade가 모두 같은 문구(미분석)라 두 곳 모두에서 보여야 한다
    const unanalyzedLabels = within(unanalyzedItem).getAllByText(riskGradeLabel(unanalyzed.riskGrade));
    expect(unanalyzedLabels.length).toBeGreaterThanOrEqual(2);
  });

  it('항목의 「상세 보기」 링크가 그 매물의 지도 딥링크(/map?propertyId=)를 가리킨다', async () => {
    server.use(...wishlistHandlers);

    renderList();

    const target = WISHLIST_PAGE_1.items[0]!;
    await waitFor(() => expect(screen.getByText(target.district)).toBeInTheDocument());

    // closest('li') 구조 선택자는 findListItem이 이미 쓰던 방식 그대로다 — 여전히 유효하다
    const item = findListItem(target.propertyId);
    expect(within(item).getByRole('link', { name: '상세 보기' })).toHaveAttribute(
      'href',
      propertyDetailPath(target.propertyId),
    );
  });
});
