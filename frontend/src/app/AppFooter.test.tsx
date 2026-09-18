// 이 테스트가 보는 것은 「라우트별 푸터 유무」 하나다 — 푸터의 렌더링 전반은 테스트 대상이 아니다
// (테스트 전략 문서 1.1). 라우트 표를 그대로 메모리 라우터에 얹어, 지도 화면의 handle.hideFooter가
// 실제로 걸리는지 본다. 표를 테스트가 다시 적으면 라우트 표와 어긋난 채로 통과한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
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

  it('데이터 출처는 새 탭으로 여는 외부 링크다', async () => {
    const footer = await renderLoginFooter();
    const ecos = within(footer).getByRole('link', { name: '한국은행 ECOS' });

    expect(ecos).toHaveAttribute('href', 'https://ecos.bok.or.kr');
    expect(ecos).toHaveAttribute('target', '_blank');
    expect(ecos).toHaveAttribute('rel', 'noreferrer');
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
