// PropertyList 검증 — 매물 API 명세 1.3이 좌표 유무로 마커·목록 응답을 가르므로, 목록 조회가
// 좌표를 보내지 않는지(①)와 기본 정렬을 화면이 만들어 보내지 않는지(②)가 가장 틀리기 쉬운 지점이다.
// 정렬 전환 · 커서 「더 보기」 · 항목 선택 · 미분석 표기도 함께 본다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PROPERTY_SORT_LABEL, PROPERTY_SORTS } from '../../../domain/property';
import { debtRatioLabel, riskGradeLabel } from '../../../domain/risk';
import { PROPERTY_LIST_PAGE_1, PROPERTY_LIST_PAGE_2, propertyHandlers } from '../../../test/msw/handlers/property';
import { server } from '../../../test/msw/server';
import { PropertyList } from './PropertyList';

const LIST_ITEM_1 = PROPERTY_LIST_PAGE_1.items[0]!;
const LIST_ITEM_2 = PROPERTY_LIST_PAGE_2.items[0]!;

function renderList() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const onSelect = vi.fn();
  render(
    <QueryClientProvider client={queryClient}>
      <PropertyList filter={{}} onSelect={onSelect} />
    </QueryClientProvider>,
  );
  return onSelect;
}

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

describe('PropertyList', () => {
  it('목록 요청에 좌표(minLat · maxLat · minLng · maxLng)를 보내지 않는다', async () => {
    server.use(...propertyHandlers);
    const tracker = trackRequestedUrls();

    renderList();

    await waitFor(() => expect(screen.getByText(LIST_ITEM_1.address)).toBeInTheDocument());

    const [requestUrl] = tracker.listUrls();
    expect(requestUrl).toBeDefined();
    const params = new URL(requestUrl!).searchParams;
    expect(params.has('minLat')).toBe(false);
    expect(params.has('maxLat')).toBe(false);
    expect(params.has('minLng')).toBe(false);
    expect(params.has('maxLng')).toBe(false);

    tracker.stop();
  });

  it('정렬을 고르지 않은 첫 요청에는 sort 파라미터가 없다', async () => {
    server.use(...propertyHandlers);
    const tracker = trackRequestedUrls();

    renderList();

    await waitFor(() => expect(screen.getByText(LIST_ITEM_1.address)).toBeInTheDocument());

    const [requestUrl] = tracker.listUrls();
    expect(requestUrl).toBeDefined();
    expect(new URL(requestUrl!).searchParams.has('sort')).toBe(false);

    tracker.stop();
  });

  it('정렬을 고르면 그 값이 sort로 나가고 목록이 다시 조회된다', async () => {
    server.use(...propertyHandlers);
    const tracker = trackRequestedUrls();
    const sortOption = PROPERTY_SORTS[0];

    renderList();

    await waitFor(() => expect(screen.getByText(LIST_ITEM_1.address)).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('정렬'), { target: { value: sortOption } });

    await waitFor(() => {
      const urls = tracker.listUrls();
      const lastUrl = urls[urls.length - 1];
      expect(lastUrl).toBeDefined();
      expect(new URL(lastUrl!).searchParams.get('sort')).toBe(sortOption);
    });

    // 고른 값이 select에 그대로 반영된다 — 값을 하드코딩하지 않고 도메인 상수를 그대로 쓴다
    expect(screen.getByLabelText('정렬')).toHaveValue(sortOption);
    // PROPERTY_SORT_LABEL 매핑으로 그 정렬의 문구도 select 안에 있어야 한다(문구를 하드코딩하지 않는다)
    expect(screen.getByText(PROPERTY_SORT_LABEL[sortOption])).toBeInTheDocument();

    tracker.stop();
  });

  it('「더 보기」를 누르면 다음 요청의 cursor가 이전 쪽 응답의 nextCursor 그대로이고, 마지막 쪽에서 사라진다', async () => {
    server.use(...propertyHandlers);
    const tracker = trackRequestedUrls();

    renderList();

    await waitFor(() => expect(screen.getByText(LIST_ITEM_1.address)).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));

    await waitFor(() => expect(screen.getByText(LIST_ITEM_2.address)).toBeInTheDocument());

    const urls = tracker.listUrls();
    expect(urls.length).toBeGreaterThanOrEqual(2);
    expect(new URL(urls[1]!).searchParams.get('cursor')).toBe(PROPERTY_LIST_PAGE_1.nextCursor);

    // 둘째 쪽은 hasNext: false라 「더 보기」가 더는 없다
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument();

    tracker.stop();
  });

  it('항목을 누르면 onSelect가 그 매물의 propertyId로 호출된다', async () => {
    server.use(...propertyHandlers);
    const onSelect = renderList();

    await waitFor(() => expect(screen.getByText(LIST_ITEM_1.address)).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: `${LIST_ITEM_1.address} 상세 보기` }));

    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith(LIST_ITEM_1.propertyId);
  });

  it('미분석 매물(riskGrade · debtRatio가 null)은 domain/risk.ts의 문구로 표시되고 깨지지 않는다', async () => {
    server.use(...propertyHandlers);
    renderList();

    await waitFor(() => expect(screen.getByText(LIST_ITEM_1.address)).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    await waitFor(() => expect(screen.getByText(LIST_ITEM_2.address)).toBeInTheDocument());

    // 미분석 매물 항목의 상세 보기 버튼이 렌더된다 — 렌더가 깨지지 않았다는 증거
    expect(screen.getByRole('button', { name: `${LIST_ITEM_2.address} 상세 보기` })).toBeInTheDocument();

    const item = screen.getByText(LIST_ITEM_2.address).closest('li');
    expect(item).not.toBeNull();

    // 등급 배지 — Badge 엘리먼트의 텍스트 전체가 리스크 문구 그대로다(레이아웃 맵 재구성 전과 같은 자리)
    expect(within(item!).getByText(riskGradeLabel(LIST_ITEM_2.riskGrade))).toBeInTheDocument();
    // 전세가율은 이번 재구성으로 한 문장(스펙 줄) 안에 합쳐졌다 — 그 문장 안에 미분석 문구가 있는지를
    // 문장 단위로 확인한다. getAllByText 개수 세기 대신 자리마다 잡는 단언으로 바꿔 배지와 겹치지 않게 한다
    expect(
      within(item!).getByText((content) => content.includes(`전세가율 ${debtRatioLabel(LIST_ITEM_2.debtRatio)}`)),
    ).toBeInTheDocument();
  });
});
