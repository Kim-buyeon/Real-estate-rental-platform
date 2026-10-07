// LoginPage 리다이렉트 검증 — 이슈 117 검토에서 지적된 회귀 구멍이다. 경로 분리(#116)로 `/`의 뜻이
// 「지도」에서 「메인」으로 바뀌었지만 LoginPage 코드는 한 줄도 바뀌지 않았다. 여기서 리다이렉트 목적지가
// 여전히 옳은지(= 라우트 표가 가리키는 실제 화면인지)를 라우터를 거쳐 확인한다 — 컴포넌트만 직접 렌더하면
// 「어느 경로로 가는가」라는 이 테스트의 값어치가 사라진다. 라우트 표는 다시 적지 않고 app/router의 것을
// 그대로 얹는다(AppFooter.test.tsx와 같은 방식).
//
// LoginPage는 로그인 성공과 「이미 로그인한 채 진입」 둘 다 같은 이펙트(isAuthenticated)로 이동한다
// (LoginPage.tsx 문서 주석) — 마운트 전에 세션을 채워 두면 두 경우를 한 방식으로 재현할 수 있다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { routes } from '../app/router';
import { setTokens } from '../session/store';
import { notificationHandlers } from '../test/msw/handlers/notification';
import { propertyHandlers } from '../test/msw/handlers/property';
import { server } from '../test/msw/server';

// h1 은 줄바꿈(<br />)으로 두 줄이라 접근 이름에 공백이 들어가지 않는다 — 공백 유무와 무관하게 맞춘다
const MAIN_HEADING = /전세사기 위험 등급,\s*계약 전에 확인하세요/;

/**
 * 로그인 상태로 만든 뒤 그 경로에 렌더한다 — LoginPage 마운트 시점에 이미 isAuthenticated가 참이다.
 * 로그인 상태의 AppShell은 헤더의 읽지 않은 수를 위해 항상 알림 목록을 조회한다(app/AppShell.tsx) —
 * 라우트와 무관하게 뜨는 요청이라 여기서 한 번만 목킹한다.
 */
function renderLoggedInAt(path: string) {
  server.use(...notificationHandlers);
  setTokens({ accessToken: 'test-access-token', refreshToken: 'test-refresh-token' });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

// 라우터 전환 + MainPage의 쿼리 마운트까지 거치는 단언이라 기본 1000ms보다 넉넉히 둔다 — 전체 스위트를
// 병렬로 돌릴 때(23개 jsdom 환경 동시 생성) 개별 파일 실행보다 느려질 수 있다
const NAVIGATION_TIMEOUT = { timeout: 5000 };

describe('LoginPage 리다이렉트', () => {
  it('redirect 파라미터가 없으면 메인(/)으로 이동한다', async () => {
    // 메인은 최근 등록 매물을 조회한다 — AppFooter.test.tsx의 「메인 화면」 케이스와 같은 이유로 필요하다
    server.use(...propertyHandlers);

    renderLoggedInAt('/login');

    expect(await screen.findByRole('heading', { name: MAIN_HEADING }, NAVIGATION_TIMEOUT)).toBeInTheDocument();
  });

  it('redirect 파라미터가 있으면 그 경로로 이동한다', async () => {
    renderLoggedInAt('/login?redirect=%2Fsignup');

    expect(await screen.findByRole('heading', { name: '회원가입' }, NAVIGATION_TIMEOUT)).toBeInTheDocument();
  });

  it('같은 오리진이 아닌 redirect(오픈 리다이렉트)는 무시하고 메인(/)으로 이동한다', async () => {
    server.use(...propertyHandlers);

    renderLoggedInAt(`/login?redirect=${encodeURIComponent('//evil.com')}`);

    expect(await screen.findByRole('heading', { name: MAIN_HEADING }, NAVIGATION_TIMEOUT)).toBeInTheDocument();
  });

  // 브라우저는 역슬래시를 슬래시로, 탭 · 줄바꿈은 지워 읽는다 — 접두 검사만으로는 둘 다 //evil.com 으로 새어 나간다
  it.each(['/\\evil.com', '/\t/evil.com'])('역슬래시 · 제어 문자로 위장한 외부 redirect(%j)도 메인(/)으로 보낸다', async (redirect) => {
    server.use(...propertyHandlers);

    renderLoggedInAt(`/login?redirect=${encodeURIComponent(redirect)}`);

    expect(await screen.findByRole('heading', { name: MAIN_HEADING }, NAVIGATION_TIMEOUT)).toBeInTheDocument();
  });
});

describe('LoginPage 제목 역할 클래스', () => {
  it('h1 에 원 역할과 모바일 역할 클래스가 함께 붙는다', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const router = createMemoryRouter(routes, { initialEntries: ['/login'] });
    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    const h1 = await screen.findByRole('heading', { level: 1 });
    expect(h1).toHaveClass('type-heading-1', 'type-heading-1-mobile');
  });
});
