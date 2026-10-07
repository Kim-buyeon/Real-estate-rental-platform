// 이 테스트가 보는 것은 「라우트별 푸터 유무」 하나다 — 푸터의 렌더링 전반은 테스트 대상이 아니다
// (테스트 전략 문서 1.1). 라우트 표를 그대로 메모리 라우터에 얹어, 지도 화면의 handle.hideFooter가
// 실제로 걸리는지 본다. 표를 테스트가 다시 적으면 라우트 표와 어긋난 채로 통과한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { COMING_SOON_MESSAGE } from '../components/ui';
import { propertyHandlers } from '../test/msw/handlers/property';
import { server } from '../test/msw/server';
import { routes } from './router';

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe('AppFooter', () => {
  it('지도 화면(/map)에는 푸터를 렌더하지 않는다', async () => {
    // 지도 화면은 진입만으로 자치구 집계 · 목록을 부른다. jsdom에는 SDK가 없어 안내가 뜬다.
    // /map은 lazy 라우트라 청크가 로드될 때까지 findBy*로 기다린다 — getBy*는 로드 전에 실패한다
    server.use(...propertyHandlers);

    renderAt('/map');

    expect(await screen.findByText('지도를 불러오지 못했습니다. 새로고침해 주세요.')).toBeInTheDocument();
    expect(screen.queryByRole('contentinfo')).not.toBeInTheDocument();
  });

  it('메인 화면(/)에는 푸터가 붙는다 — 지도 화면이 이 경로를 떠나도 푸터 유무 결정(이슈 112)은 지도에만 걸린다', async () => {
    // 메인은 최근 등록 매물을 조회한다 — 지도와 같은 목록 핸들러가 필요하다
    server.use(...propertyHandlers);

    renderAt('/');

    expect(await screen.findByRole('contentinfo')).toBeInTheDocument();
  });

  it('지도 밖의 화면에는 푸터가 붙는다', async () => {
    const footer = await renderLoginFooter();

    expect(
      screen.getByText('위험도 판정과 대출 한도는 공개 데이터에 근거한 참고 정보이며 법적 효력이 없습니다.'),
    ).toBeInTheDocument();
    expect(screen.getByText('© 2026 전월세 부동산 금융 플랫폼')).toBeInTheDocument();
    expect(footer).toBeInTheDocument();
  });

  it('비로그인 상태에서도 「내 정보」 링크를 감추지 않는다 — 누르면 가드가 로그인으로 보낸다', async () => {
    const footer = await renderLoginFooter();
    const scoped = within(footer);

    expect(scoped.getByRole('link', { name: '관심 매물' })).toHaveAttribute('href', '/me/wishlist');
    expect(scoped.getByRole('link', { name: '알림 설정' })).toHaveAttribute(
      'href',
      '/me/notification-subscriptions',
    );
  });

  it('데이터 출처는 링크가 아닌 텍스트다', async () => {
    const footer = await renderLoginFooter();
    const scoped = within(footer);

    expect(scoped.getByText('한국은행 ECOS')).toBeInTheDocument();
    expect(scoped.queryByRole('link', { name: '한국은행 ECOS' })).not.toBeInTheDocument();
    expect(footer.querySelectorAll('a[target="_blank"]')).toHaveLength(0);
  });

  it('GitHub · 학습 · 포트폴리오 문구가 없다', async () => {
    const footer = await renderLoginFooter();

    expect(footer).not.toHaveTextContent(/GitHub/i);
    expect(footer).not.toHaveTextContent('학습');
    expect(footer).not.toHaveTextContent('포트폴리오');
  });

  it('등기 안내는 예시임과 원본 확인을 말한다', async () => {
    const footer = await renderLoginFooter();

    expect(
      within(footer).getByText('등기 정보는 예시이며, 계약 전 등기부등본 원본을 반드시 확인하세요.'),
    ).toBeInTheDocument();
  });

  it('사이트맵은 6열이고 제목이 순서대로 놓인다', async () => {
    const footer = await renderLoginFooter();
    const nav = within(footer).getByRole('navigation', { name: '사이트맵' });

    expect(within(nav).getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      '매물',
      '내 정보',
      '대출',
      '상담',
      '고객지원',
      '데이터 출처',
    ]);
  });

  it('준비 중 항목은 버튼이고 누르면 알림만 뜬다', async () => {
    const footer = await renderLoginFooter();
    expect(screen.getByRole('status')).toBeEmptyDOMElement();

    fireEvent.click(within(footer).getByRole('button', { name: '공지사항' }));

    expect(within(screen.getByRole('status')).getByText(COMING_SOON_MESSAGE)).toBeInTheDocument();
  });

  it('「지도 탐색」 링크가 없다 — 지도로 가는 길은 상단 메뉴 하나다', async () => {
    const footer = await renderLoginFooter();

    expect(within(footer).queryByRole('link', { name: '지도 탐색' })).not.toBeInTheDocument();
    expect(within(footer).queryByText('지도 탐색')).not.toBeInTheDocument();
    const mapHrefs = within(footer)
      .queryAllByRole('link')
      .filter((a) => a.getAttribute('href')?.startsWith('/map'));
    expect(mapHrefs).toHaveLength(0);
  });

  it('TOP 을 누르면 문서 최상단으로 올린다', async () => {
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
    const footer = await renderLoginFooter();

    fireEvent.click(within(footer).getByRole('button', { name: 'TOP' }));

    expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: 'smooth' });
    scrollTo.mockRestore();
  });
});

/** 푸터가 붙는 화면 중 공개 라우트 하나. lazy 라우트라 푸터가 나타날 때까지 기다린다 */
async function renderLoginFooter() {
  renderAt('/login');
  return screen.findByRole('contentinfo');
}
