// USER-06 재설정 메일 요청 화면 — 응답으로 가입 여부를 드러내지 않는가(명세 1.3)를 본다. 서버가 무엇을
// 돌려주든 같은 안내이고, 서버에 닿지 못한 경우만 다시 시도하라는 오류다. 로그인 화면의 진입 링크와
// 인증 진입 화면 성질(헤더 로그인 링크가 redirect를 싣지 않는다)은 라우트 표를 그대로 얹어 확인한다 —
// 표를 테스트가 다시 적으면 어긋난 채로 통과한다 (AppShell.test.tsx와 같은 방식).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { routes } from '../app/router';
import { userHandlers } from '../test/msw/handlers/user';
import { server } from '../test/msw/server';

const REQUESTED_NOTICE = '입력한 이메일로 가입한 계정이 있으면 비밀번호 재설정 링크를 보냈습니다.';
const EMAIL = 'user@example.com';

function renderAt(path: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

async function submitEmail(email: string) {
  fireEvent.change(await screen.findByLabelText('이메일'), { target: { value: email } });
  fireEvent.click(screen.getByRole('button', { name: '재설정 링크 받기' }));
}

describe('PasswordResetRequestPage', () => {
  it('로그인 화면의 「비밀번호 찾기」 링크로 들어오고, 헤더 로그인 링크는 redirect를 싣지 않는다', async () => {
    renderAt('/login');

    fireEvent.click(await screen.findByRole('link', { name: '비밀번호 찾기' }));

    expect(await screen.findByRole('heading', { name: '비밀번호 찾기' })).toBeInTheDocument();
    expect(within(screen.getByRole('banner')).getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login');
  });

  it('입력한 이메일을 본문으로 보내고 200이면 안내로 바꾼다', async () => {
    let body: unknown;
    server.use(
      http.post('/api/auth/password-reset', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ success: true, data: null });
      }),
    );
    renderAt('/password-reset');

    await submitEmail(EMAIL);

    expect(await screen.findByText(REQUESTED_NOTICE, { exact: false })).toBeInTheDocument();
    expect(body).toEqual({ email: EMAIL });
    expect(screen.queryByLabelText('이메일')).not.toBeInTheDocument();
  });

  // 서버가 응답한 실패가 화면에서 갈리면 그 차이로 가입 여부를 짐작할 수 있다 — 어떤 코드든 같은 안내다
  it.each([
    [400, 'INVALID_REQUEST', '요청 형식이 올바르지 않습니다.'],
    [500, 'INTERNAL_ERROR', '서버 오류가 발생했습니다.'],
  ])('서버가 %i %s로 응답해도 같은 안내이고 서버 문구를 보이지 않는다', async (status, code, message) => {
    server.use(
      http.post('/api/auth/password-reset', () =>
        HttpResponse.json({ success: false, error: { code, message } }, { status }),
      ),
    );
    renderAt('/password-reset');

    await submitEmail(EMAIL);

    expect(await screen.findByText(REQUESTED_NOTICE, { exact: false })).toBeInTheDocument();
    expect(screen.queryByText(message)).not.toBeInTheDocument();
  });

  it('서버에 닿지 못하면 안내 대신 다시 시도하라는 오류를 보이고 폼을 남긴다', async () => {
    server.use(http.post('/api/auth/password-reset', () => HttpResponse.error()));
    renderAt('/password-reset');

    await submitEmail(EMAIL);

    expect(await screen.findByRole('alert')).toHaveTextContent('다시 시도해 주세요');
    expect(screen.queryByText(REQUESTED_NOTICE, { exact: false })).not.toBeInTheDocument();
    expect(screen.getByLabelText('이메일')).toHaveValue(EMAIL);
  });

  it('명세 예시 핸들러(항상 200)로도 같은 안내가 뜬다', async () => {
    server.use(...userHandlers);
    renderAt('/password-reset');

    await submitEmail('unknown@example.com');

    expect(await screen.findByText(REQUESTED_NOTICE, { exact: false })).toBeInTheDocument();
  });
});
