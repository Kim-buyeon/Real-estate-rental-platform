// USER-06 새 비밀번호 확정 화면 — 토큰을 쿼리에서 읽어 본문으로만 보내는가, 성공 · 무효 토큰 · 규칙 위반 ·
// 토큰 없음이 명세 1.3대로 갈리는가를 본다. 성공 뒤 이동 목적지가 라우트 표의 실제 로그인 화면인지 보려고
// 라우트 표를 그대로 메모리 라우터에 얹는다 (LoginPage.test.tsx와 같은 방식).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { routes } from '../app/router';
import { PASSWORD_RESET_TOKEN, userHandlers } from '../test/msw/handlers/user';
import { server } from '../test/msw/server';

const CONFIRM_PATH = `/password-reset/confirm?token=${PASSWORD_RESET_TOKEN}`;
const NEW_PASSWORD = 'N3wP@ssw0rd!';

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
  return router;
}

async function fillAndSubmit(newPassword: string, confirm: string) {
  fireEvent.change(await screen.findByLabelText('새 비밀번호'), { target: { value: newPassword } });
  fireEvent.change(screen.getByLabelText('새 비밀번호 확인'), { target: { value: confirm } });
  fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
}

describe('PasswordResetConfirmPage', () => {
  it('쿼리의 토큰을 본문으로 보내고 성공하면 로그인 화면으로 가 안내한다 — 토큰은 화면에 없다', async () => {
    let body: unknown;
    server.use(
      http.post('/api/auth/password-reset/confirm', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ success: true, data: null });
      }),
    );
    const router = renderAt(CONFIRM_PATH);

    await screen.findByLabelText('새 비밀번호');
    expect(document.body).not.toHaveTextContent(PASSWORD_RESET_TOKEN);
    // 헤더 로그인 링크가 토큰 실린 경로를 redirect로 싣지 않는다 — 인증 진입 화면
    expect(within(screen.getByRole('banner')).getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login');

    await fillAndSubmit(NEW_PASSWORD, NEW_PASSWORD);

    expect(await screen.findByText('비밀번호를 바꿨습니다. 새 비밀번호로 로그인해 주세요.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument();
    expect(body).toEqual({ token: PASSWORD_RESET_TOKEN, newPassword: NEW_PASSWORD });
    // 자동 로그인하지 않는다 — 명세 1.3 「재설정은 로그인을 대신하지 않는다」
    expect(router.state.location.pathname).toBe('/login');
  });

  it('명세 예시 핸들러로도 성공 경로를 탄다', async () => {
    server.use(...userHandlers);
    renderAt(CONFIRM_PATH);

    await fillAndSubmit(NEW_PASSWORD, NEW_PASSWORD);

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument();
  });

  it('무효 토큰이면 서버 문구와 다시 요청 링크를 보이고 폼을 거둔다', async () => {
    const message = '비밀번호 재설정 링크가 유효하지 않습니다. 다시 요청해 주세요.';
    server.use(
      http.post('/api/auth/password-reset/confirm', () =>
        HttpResponse.json({ success: false, error: { code: 'AUTH_RESET_TOKEN_INVALID', message } }, { status: 400 }),
      ),
    );
    renderAt(CONFIRM_PATH);

    await fillAndSubmit(NEW_PASSWORD, NEW_PASSWORD);

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expect(screen.getByRole('link', { name: '재설정 링크 다시 받기' })).toHaveAttribute('href', '/password-reset');
    expect(screen.queryByLabelText('새 비밀번호')).not.toBeInTheDocument();
    // 토큰 없음과 같은 모양이다 — 폼 안내문이 무효 안내 위에 남지 않는다
    expect(screen.queryByText('새로 쓸 비밀번호를 입력해 주세요.')).not.toBeInTheDocument();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  it('비밀번호 규칙 위반(field newPassword)은 그 입력에 서버 문구 그대로 보인다', async () => {
    const message = '비밀번호는 8자 이상이며 영문 · 숫자 · 특수문자를 포함해야 합니다.';
    server.use(
      http.post('/api/auth/password-reset/confirm', () =>
        HttpResponse.json(
          { success: false, error: { code: 'INVALID_REQUEST', message, field: 'newPassword' } },
          { status: 400 },
        ),
      ),
    );
    renderAt(CONFIRM_PATH);

    await fillAndSubmit('short', 'short');

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.getByLabelText('새 비밀번호')).toHaveAttribute('aria-invalid', 'true');
  });

  it('필드 없는 오류는 폼 위 Alert에 서버 문구 그대로 보이고 폼을 남긴다', async () => {
    const message = '서버 오류가 발생했습니다.';
    server.use(
      http.post('/api/auth/password-reset/confirm', () =>
        HttpResponse.json({ success: false, error: { code: 'INTERNAL_ERROR', message } }, { status: 500 }),
      ),
    );
    renderAt(CONFIRM_PATH);

    await fillAndSubmit(NEW_PASSWORD, NEW_PASSWORD);

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expect(screen.getByLabelText('새 비밀번호')).toHaveAttribute('aria-invalid', 'false');
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '재설정 링크 다시 받기' })).not.toBeInTheDocument();
  });

  it('두 입력이 다르면 요청하지 않고 확인 입력에 알린다', async () => {
    let requestCount = 0;
    server.use(
      http.post('/api/auth/password-reset/confirm', () => {
        requestCount += 1;
        return HttpResponse.json({ success: true, data: null });
      }),
    );
    renderAt(CONFIRM_PATH);

    await fillAndSubmit(NEW_PASSWORD, `${NEW_PASSWORD}x`);

    expect(await screen.findByText('새 비밀번호가 서로 다릅니다.', { exact: false })).toBeInTheDocument();
    expect(screen.getByLabelText('새 비밀번호 확인')).toHaveAttribute('aria-invalid', 'true');
    expect(requestCount).toBe(0);
  });

  // 등록되지 않은 요청은 setup의 onUnhandledRequest: 'error'로 실패한다 — 요청이 나가지 않는 것도 함께 본다
  it.each(['/password-reset/confirm', '/password-reset/confirm?token=', '/password-reset/confirm?token=%20'])(
    '토큰이 없으면(%s) 폼 대신 다시 요청 안내를 보인다',
    async (path) => {
      renderAt(path);

      expect(await screen.findByRole('link', { name: '재설정 링크 다시 받기' })).toHaveAttribute('href', '/password-reset');
      expect(screen.getByRole('alert')).toHaveTextContent('비밀번호 재설정 링크가 올바르지 않습니다.');
      expect(screen.queryByLabelText('새 비밀번호')).not.toBeInTheDocument();
      expect(screen.queryByText('새로 쓸 비밀번호를 입력해 주세요.')).not.toBeInTheDocument();
    },
  );
});
