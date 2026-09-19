// SDK가 없는 환경의 동작만 검증한다 — 렌더링 전반은 대상이 아니다 (docs/architecture/testing.md 1.1).
// jsdom에는 window.kakao가 없으므로 SDK를 가짜로 만들지 않고 그 상태를 그대로 쓴다.
//
// 딥링크(이슈 104)는 useSearchParams를 쓰므로 라우터 컨텍스트가 있어야 렌더된다 —
// createMemoryRouter + RouterProvider로 감싸고, initialEntries로 진입 경로(?propertyId=)를 준다.
// router.state.location으로 히스토리 · 검색 파라미터 변화를 관찰한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { propertyDetailPath } from '../lib/routes';
import { PROPERTY_DETAIL, PROPERTY_LIST_PAGE_1, propertyHandlers } from '../test/msw/handlers/property';
import { riskHandlers } from '../test/msw/handlers/risk';
import { server } from '../test/msw/server';
import MapPage from './MapPage';

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

function renderMapPage(path = '/map') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '/map', element: <MapPage /> }], { initialEntries: [path] });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

describe('MapPage', () => {
  it('카카오맵 SDK가 없으면 자치구 집계가 도착해도 죽지 않고 안내를 보여준다', async () => {
    server.use(...propertyHandlers);

    renderMapPage();

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

    renderMapPage();

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

    renderMapPage();

    await waitFor(() => expect(screen.getByText(LIST_ITEM.address)).toBeInTheDocument());
    // 목록의 첫 항목(propertyId 1024)이 매물 상세(PROPERTY_DETAIL, 같은 propertyId)와 같은 매물이다
    expect(LIST_ITEM.propertyId).toBe(PROPERTY_DETAIL.propertyId);

    fireEvent.click(screen.getByRole('button', { name: `${LIST_ITEM.address} 상세 보기` }));

    await waitFor(() => expect(screen.getByRole('tab', { name: '상세' })).toHaveAttribute('aria-selected', 'true'));
    // 상세 패널이 그 매물로 열렸다 — 패널에만 있는 「상세 닫기」 버튼으로 확인한다
    await waitFor(() => expect(screen.getByRole('button', { name: '상세 닫기' })).toBeInTheDocument());
  });

  // 좁은 화면의 지도 ↔ 목록 전환 토글 (이슈 114 계획 「정한 것」). display: none으로 감추므로
  // jsdom에는 지도 · 패널 DOM이 항상 둘 다 있다 — 여기서는 CSS 보임 여부가 아니라 토글 버튼 문구로
  // narrowView 상태 전이를 확인한다. 지도는 SDK가 없어(jsdom) 렌더되지 않지만 토글 버튼은 MapPage가
  // 소유한 상태라 SDK와 무관하게 렌더된다.
  it('좁은 화면 전환 토글 버튼 문구가 목록 → 지도 → 목록으로 바뀐다', async () => {
    server.use(...propertyHandlers);

    renderMapPage();

    await waitFor(() => expect(screen.getByText(LIST_ITEM.address)).toBeInTheDocument());

    // 초기값은 지도가 보이는 쪽(MAP_VIEW)이라 버튼은 반대쪽으로 가는 「목록」이다
    expect(screen.getByRole('button', { name: '목록' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '목록' }));
    expect(screen.getByRole('button', { name: '지도' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '목록' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '지도' }));
    expect(screen.getByRole('button', { name: '목록' })).toBeInTheDocument();
  });

  it('목록 항목에서 상세를 열면 좁은 화면 토글이 패널 쪽(지도 문구)으로 넘어간다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);

    renderMapPage();

    await waitFor(() => expect(screen.getByText(LIST_ITEM.address)).toBeInTheDocument());
    // 상세를 열기 전에는 지도가 보이는 쪽 — 버튼 문구가 「목록」이다
    expect(screen.getByRole('button', { name: '목록' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: `${LIST_ITEM.address} 상세 보기` }));

    // 상세가 목록과 같은 자리를 덮으므로 좁은 화면은 패널 쪽(narrowView: 'panel')으로 넘어간다 —
    // 버튼은 지도로 돌아가는 「지도」로 바뀐다
    await waitFor(() => expect(screen.getByRole('button', { name: '지도' })).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '목록' })).not.toBeInTheDocument();
  });

  // ── 딥링크(이슈 104) ────────────────────────────────────────────────────

  it('?propertyId로 들어오면 상세 탭이 곧바로 열리고 지도가 그 매물의 자치구 단계로 간다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);

    renderMapPage(propertyDetailPath(PROPERTY_DETAIL.propertyId));

    // 탭이 상세이고 그 매물의 상세가 보인다
    await waitFor(() => expect(screen.getByRole('tab', { name: '상세' })).toHaveAttribute('aria-selected', 'true'));
    await waitFor(() => expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument());

    // 지도가 상세 응답의 district(강서구) 단계로 간다 — 자치구 선택기 값과 「← 서울 전체」 버튼으로
    // 확인한다. 이것이 빠지면 패널은 열렸는데 지도가 무관한 자리를 비춘다(이슈 104 계획 핵심)
    await waitFor(() => expect(screen.getByLabelText('자치구')).toHaveValue(PROPERTY_DETAIL.district));
    expect(screen.getByRole('button', { name: '← 서울 전체' })).toBeInTheDocument();
  });

  it('propertyId가 숫자가 아니면 없는 것으로 다뤄 목록이 기본 탭으로 열린다', async () => {
    server.use(...propertyHandlers);

    renderMapPage('/map?propertyId=abc');

    await waitFor(() => expect(screen.getByText(LIST_ITEM.address)).toBeInTheDocument());
    expect(screen.getByRole('tab', { name: '목록' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: '상세' })).toBeDisabled();
  });

  it('propertyId가 0 이하이면 없는 것으로 다뤄 목록이 기본 탭으로 열린다', async () => {
    server.use(...propertyHandlers);

    renderMapPage('/map?propertyId=0');

    await waitFor(() => expect(screen.getByText(LIST_ITEM.address)).toBeInTheDocument());
    expect(screen.getByRole('tab', { name: '목록' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: '상세' })).toBeDisabled();
  });

  it('없는 매물 번호(404 PROPERTY_NOT_FOUND)면 Alert가 뜨고 파라미터가 사라진다', async () => {
    const message = '존재하지 않는 매물입니다.';
    server.use(
      http.get('/api/properties/:propertyId', () =>
        HttpResponse.json({ success: false, error: { code: 'PROPERTY_NOT_FOUND', message } }, { status: 404 }),
      ),
      ...propertyHandlers,
      ...riskHandlers,
    );

    const router = renderMapPage(propertyDetailPath(9999));

    // getByRole('alert')는 쓰지 않는다 — SDK가 없는 지도(MapExplorer)도 role=alert 안내를 함께
    // 띄우므로(이 화면의 첫 테스트) 자리가 둘이다. 문구로 특정한다
    await waitFor(() => expect(screen.getByText(message)).toBeInTheDocument());
    // 파라미터가 지워진다 — 잘못된 링크를 새로고침마다 되풀이하지 않기 위함이다
    await waitFor(() => expect(router.state.location.search).toBe(''));
    // 매물이 사라졌으니 목록 탭으로 돌아간다
    await waitFor(() => expect(screen.getByRole('tab', { name: '목록' })).toHaveAttribute('aria-selected', 'true'));
  });

  it('5xx 오류에서는 Alert가 뜨지만 파라미터는 남는다', async () => {
    const message = '일시적인 오류로 매물을 불러오지 못했습니다.';
    server.use(
      http.get('/api/properties/:propertyId', () =>
        HttpResponse.json({ success: false, error: { code: 'INTERNAL_ERROR', message } }, { status: 500 }),
      ),
      ...propertyHandlers,
      ...riskHandlers,
    );

    const router = renderMapPage(propertyDetailPath(PROPERTY_DETAIL.propertyId));

    await waitFor(() => expect(screen.getByText(message)).toBeInTheDocument());
    // 404만 지운다 — 지금 못 불러온 것이라 사용자가 가리키던 매물을 잃지 않는다
    expect(new URLSearchParams(router.state.location.search).get('propertyId')).toBe(
      String(PROPERTY_DETAIL.propertyId),
    );
  });

  it('네트워크 오류에서도 파라미터는 남는다', async () => {
    server.use(
      http.get('/api/properties/:propertyId', () => HttpResponse.error()),
      ...propertyHandlers,
      ...riskHandlers,
    );

    const router = renderMapPage(propertyDetailPath(PROPERTY_DETAIL.propertyId));

    // 패널 안(aside 「매물 상세」)의 오류로 특정한다 — SDK 없는 지도의 role=alert 안내와 겹친다
    const panel = await screen.findByRole('complementary', { name: '매물 상세' });
    await waitFor(() => expect(within(panel).getByRole('alert')).toBeInTheDocument());
    expect(new URLSearchParams(router.state.location.search).get('propertyId')).toBe(
      String(PROPERTY_DETAIL.propertyId),
    );
  });

  it('상세를 열고 뒤로가면 닫힌다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);
    const router = renderMapPage();

    await waitFor(() => expect(screen.getByText(LIST_ITEM.address)).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: `${LIST_ITEM.address} 상세 보기` }));
    await waitFor(() => expect(screen.getByRole('button', { name: '상세 닫기' })).toBeInTheDocument());

    await router.navigate(-1);

    await waitFor(() => expect(screen.queryByRole('button', { name: '상세 닫기' })).not.toBeInTheDocument());
    expect(screen.getByRole('tab', { name: '목록' })).toHaveAttribute('aria-selected', 'true');
  });

  it('상세를 닫고 뒤로가면 다시 열린다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);
    const router = renderMapPage(propertyDetailPath(PROPERTY_DETAIL.propertyId));

    await waitFor(() => expect(screen.getByRole('button', { name: '상세 닫기' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '상세 닫기' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '상세 닫기' })).not.toBeInTheDocument());

    await router.navigate(-1);

    await waitFor(() => expect(screen.getByRole('button', { name: '상세 닫기' })).toBeInTheDocument());
  });

  // ── 목록 탭 전환(이슈 140) ──────────────────────────────────────────────

  it('상세가 열린 채 목록 탭으로 가면 URL의 매물 번호가 지워지고 히스토리에 칸이 생기지 않는다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);
    const router = renderMapPage();

    await waitFor(() => expect(screen.getByText(LIST_ITEM.address)).toBeInTheDocument());
    // 열기는 push다 — 히스토리: [/map, /map?propertyId=]
    fireEvent.click(screen.getByRole('button', { name: `${LIST_ITEM.address} 상세 보기` }));
    await waitFor(() => expect(screen.getByRole('button', { name: '상세 닫기' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('tab', { name: '목록' }));

    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(router.state.historyAction).toBe('REPLACE');
    expect(screen.getByRole('tab', { name: '목록' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: '상세' })).toBeDisabled();

    // replace라 칸이 늘지 않았다 — 뒤로가기는 상세를 연 칸이 아니라 그 앞(/map)으로 간다.
    // push였다면 [/map, /map?propertyId=, /map]이 되어 뒤로가기가 상세를 다시 연다
    await router.navigate(-1);
    await waitFor(() => expect(router.state.historyAction).toBe('POP'));
    expect(router.state.location.search).toBe('');
    expect(screen.queryByRole('button', { name: '상세 닫기' })).not.toBeInTheDocument();
  });

  it('목록 탭으로 간 뒤 목록에서 매물을 다시 고르면 상세가 열리고 URL에 번호가 붙는다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);
    const router = renderMapPage(propertyDetailPath(PROPERTY_DETAIL.propertyId));

    await waitFor(() => expect(screen.getByRole('button', { name: '상세 닫기' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('tab', { name: '목록' }));
    await waitFor(() => expect(screen.getByText(LIST_ITEM.address)).toBeInTheDocument());
    expect(router.state.location.search).toBe('');

    fireEvent.click(screen.getByRole('button', { name: `${LIST_ITEM.address} 상세 보기` }));

    await waitFor(() => expect(screen.getByRole('tab', { name: '상세' })).toHaveAttribute('aria-selected', 'true'));
    expect(new URLSearchParams(router.state.location.search).get('propertyId')).toBe(String(LIST_ITEM.propertyId));
    expect(router.state.historyAction).toBe('PUSH');
  });

  // ── 지도 단계가 튀지 않는 경계 ──────────────────────────────────────────

  it('상세를 열어 둔 채 「← 서울 전체」로 벗어나도 다시 그 자치구를 따라가지 않는다 — 매물 하나에 한 번만 따라간다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);

    renderMapPage(propertyDetailPath(PROPERTY_DETAIL.propertyId));

    await waitFor(() => expect(screen.getByLabelText('자치구')).toHaveValue(PROPERTY_DETAIL.district));

    fireEvent.click(screen.getByRole('button', { name: '← 서울 전체' }));

    // 서울 전체로 돌아간 채 유지된다 — 자동으로 다시 그 자치구로 따라가면 사용자와 다툰다
    expect(screen.getByLabelText('자치구')).toHaveValue('');
    expect(screen.queryByRole('button', { name: '← 서울 전체' })).not.toBeInTheDocument();
    // 상세는 여전히 열려 있다 — 지도 단계와 무관하게 패널은 그대로다
    expect(screen.getByRole('button', { name: '상세 닫기' })).toBeInTheDocument();
  });
});
