// api/client.ts 로직 테스트 — 봉투 해제 · 오류 변환 · 재발급 · 파라미터 직렬화.
// 근거: frontend/CLAUDE.md API 클라이언트 절, docs/api/common.md 1.2 · 2장, docs/architecture/testing.md 1.1(프론트 로직 테스트).
// 도메인 API 함수(api/<도메인>.ts)가 아직 없으므로 request<T>()를 직접 호출한다.

import { http, HttpResponse } from 'msw';
import { describe, expect, test } from 'vitest';
import { getAccessToken, getRefreshToken, setTokens } from '../session/store';
import { server } from '../test/msw/server';
import { ApiError, request } from './client';

describe('request — 성공 봉투', () => {
  test('success:true 봉투는 data만 반환한다', async () => {
    server.use(
      http.get('/api/test-success', () => HttpResponse.json({ success: true, data: { id: 1, name: '테스트' } })),
    );

    const result = await request<{ id: number; name: string }>({ url: '/test-success' });

    expect(result).toEqual({ id: 1, name: '테스트' });
  });

  test('204 응답은 null을 반환한다', async () => {
    server.use(http.delete('/api/test-204', () => new HttpResponse(null, { status: 204 })));

    const result = await request<null>({ method: 'DELETE', url: '/test-204' });

    expect(result).toBeNull();
  });

  test('200 + 빈 본문은 null을 반환한다', async () => {
    server.use(http.get('/api/test-empty-200', () => new HttpResponse('', { status: 200 })));

    const result = await request<null>({ url: '/test-empty-200' });

    expect(result).toBeNull();
  });
});

describe('request — 오류 봉투', () => {
  test('상태 200 + success:false는 상태를 유지한 채 ApiError를 던진다', async () => {
    server.use(
      http.get('/api/test-fail-200', () =>
        HttpResponse.json({
          success: false,
          error: { code: 'PROFILE_INCOMPLETE', message: '대출 한도 계산에 필요한 자격 정보가 없습니다.', field: 'annualIncome' },
        }),
      ),
    );

    await expect(request({ url: '/test-fail-200' })).rejects.toBeInstanceOf(ApiError);
    await expect(request({ url: '/test-fail-200' })).rejects.toMatchObject({
      status: 200,
      code: 'PROFILE_INCOMPLETE',
      message: '대출 한도 계산에 필요한 자격 정보가 없습니다.',
      field: 'annualIncome',
    });
  });

  test('4xx 오류 봉투는 status · code · message · field를 담은 ApiError를 던진다', async () => {
    server.use(
      http.post('/api/test-400', () =>
        HttpResponse.json(
          { success: false, error: { code: 'INVALID_REQUEST', message: '형식이 올바르지 않습니다.', field: 'email' } },
          { status: 400 },
        ),
      ),
    );

    await expect(request({ method: 'POST', url: '/test-400' })).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_REQUEST',
      message: '형식이 올바르지 않습니다.',
      field: 'email',
    });
  });

  test('429 오류 봉투는 retryAfter를 담은 ApiError를 던진다', async () => {
    server.use(
      http.post('/api/test-429', () =>
        HttpResponse.json(
          {
            success: false,
            error: { code: 'RISK_REANALYZE_TOO_SOON', message: '재분석 요청 간격이 지나지 않았습니다.', retryAfter: '2026-07-29T03:10:00+09:00' },
          },
          { status: 429 },
        ),
      ),
    );

    await expect(request({ method: 'POST', url: '/test-429' })).rejects.toMatchObject({
      status: 429,
      code: 'RISK_REANALYZE_TOO_SOON',
      retryAfter: '2026-07-29T03:10:00+09:00',
    });
  });

  test('네트워크 실패는 NETWORK_ERROR를 던진다', async () => {
    server.use(http.get('/api/test-network', () => HttpResponse.error()));

    await expect(request({ url: '/test-network' })).rejects.toMatchObject({
      status: null,
      code: 'NETWORK_ERROR',
    });
  });

  test('봉투가 아닌 응답(502 + text/html)은 상태를 유지한 채 NETWORK_ERROR를 던진다', async () => {
    server.use(
      http.get(
        '/api/test-bad-gateway',
        () => new HttpResponse('<html><body>Bad Gateway</body></html>', { status: 502, headers: { 'Content-Type': 'text/html' } }),
      ),
    );

    await expect(request({ url: '/test-bad-gateway' })).rejects.toMatchObject({
      status: 502,
      code: 'NETWORK_ERROR',
    });
  });
});

describe('request — 재발급', () => {
  test('동시에 401이 난 두 요청은 재발급 호출 1회를 공유하고 새 토큰으로 재시도해 성공한다', async () => {
    setTokens({ accessToken: 'old-access', refreshToken: 'old-refresh' });
    let reissueCallCount = 0;

    server.use(
      http.get('/api/test-concurrent', ({ request: req }) => {
        const authorization = req.headers.get('Authorization');
        if (authorization === 'Bearer new-access') {
          return HttpResponse.json({ success: true, data: { ok: true } });
        }
        return HttpResponse.json({ success: false, error: { code: 'AUTH_TOKEN_EXPIRED', message: '토큰이 만료되었습니다.' } }, { status: 401 });
      }),
      http.post('/api/auth/reissue', () => {
        reissueCallCount += 1;
        return HttpResponse.json({
          success: true,
          data: { accessToken: 'new-access', refreshToken: 'new-refresh', tokenType: 'Bearer', expiresIn: 3600, isNewUser: false },
        });
      }),
    );

    const [first, second] = await Promise.all([
      request<{ ok: boolean }>({ url: '/test-concurrent' }),
      request<{ ok: boolean }>({ url: '/test-concurrent' }),
    ]);

    expect(first).toEqual({ ok: true });
    expect(second).toEqual({ ok: true });
    expect(reissueCallCount).toBe(1);
    expect(getAccessToken()).toBe('new-access');
    expect(getRefreshToken()).toBe('new-refresh');
  });

  test('재발급이 이미 끝난 뒤 옛 액세스 토큰으로 나간 요청은 재발급 없이 새 토큰으로 재시도해 성공한다', async () => {
    setTokens({ accessToken: 'old-access', refreshToken: 'old-refresh' });
    let reissueCallCount = 0;
    const capturedBAuthorizations: (string | null)[] = [];
    // B의 401 응답을 A의 재발급이 끝날 때까지 지연시켜 순서를 결정적으로 만든다
    let releaseB: () => void = () => {};
    const bDelay = new Promise<void>((resolve) => {
      releaseB = resolve;
    });

    server.use(
      http.get('/api/test-sequential-a', ({ request: req }) => {
        const authorization = req.headers.get('Authorization');
        if (authorization === 'Bearer new-access') {
          return HttpResponse.json({ success: true, data: { ok: true } });
        }
        return HttpResponse.json({ success: false, error: { code: 'AUTH_TOKEN_EXPIRED', message: '토큰이 만료되었습니다.' } }, { status: 401 });
      }),
      http.get('/api/test-sequential-b', async ({ request: req }) => {
        const authorization = req.headers.get('Authorization');
        capturedBAuthorizations.push(authorization);
        if (authorization === 'Bearer new-access') {
          return HttpResponse.json({ success: true, data: { ok: true } });
        }
        await bDelay;
        return HttpResponse.json({ success: false, error: { code: 'AUTH_TOKEN_EXPIRED', message: '토큰이 만료되었습니다.' } }, { status: 401 });
      }),
      http.post('/api/auth/reissue', () => {
        reissueCallCount += 1;
        return HttpResponse.json({
          success: true,
          data: { accessToken: 'new-access', refreshToken: 'new-refresh', tokenType: 'Bearer', expiresIn: 3600, isNewUser: false },
        });
      }),
    );

    // B를 먼저 보내 옛 토큰이 헤더에 실리게 하되, 응답은 A의 재발급이 끝날 때까지 서버에서 붙잡아 둔다
    const bPromise = request<{ ok: boolean }>({ url: '/test-sequential-b' });

    const aResult = await request<{ ok: boolean }>({ url: '/test-sequential-a' });
    expect(aResult).toEqual({ ok: true });
    expect(reissueCallCount).toBe(1);

    releaseB();
    const bResult = await bPromise;

    expect(bResult).toEqual({ ok: true });
    expect(reissueCallCount).toBe(1);
    expect(capturedBAuthorizations).toEqual(['Bearer old-access', 'Bearer new-access']);
  });

  test('재발급 실패는 세션을 비우고 원 401 ApiError를 던진다', async () => {
    setTokens({ accessToken: 'old-access', refreshToken: 'old-refresh' });

    server.use(
      http.get('/api/test-reissue-fail', () =>
        HttpResponse.json({ success: false, error: { code: 'AUTH_TOKEN_EXPIRED', message: '토큰이 만료되었습니다.' } }, { status: 401 }),
      ),
      http.post('/api/auth/reissue', () =>
        HttpResponse.json({ success: false, error: { code: 'AUTH_INVALID_CREDENTIAL', message: '리프레시 토큰이 유효하지 않습니다.' } }, { status: 401 }),
      ),
    );

    await expect(request({ url: '/test-reissue-fail' })).rejects.toMatchObject({
      status: 401,
      code: 'AUTH_TOKEN_EXPIRED',
    });
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  test('로그인 경로의 401 AUTH_INVALID_CREDENTIAL은 재발급 없이 그대로 던진다', async () => {
    let reissueCallCount = 0;
    server.use(
      http.post('/api/auth/login', () =>
        HttpResponse.json({ success: false, error: { code: 'AUTH_INVALID_CREDENTIAL', message: '이메일 또는 비밀번호가 올바르지 않습니다.' } }, { status: 401 }),
      ),
      http.post('/api/auth/reissue', () => {
        reissueCallCount += 1;
        return HttpResponse.json({ success: true, data: {} });
      }),
    );

    await expect(request({ method: 'POST', url: '/auth/login' })).rejects.toMatchObject({
      status: 401,
      code: 'AUTH_INVALID_CREDENTIAL',
    });
    expect(reissueCallCount).toBe(0);
  });

  // 로그인 전에 부르는 인증 경로다. 남아 있던 만료 토큰 때문에 401이 와도 재발급하지 않는다 (이슈 148)
  test.each(['/auth/password-reset', '/auth/password-reset/confirm'])(
    '비밀번호 재설정 경로(%s)의 401 AUTH_TOKEN_EXPIRED는 재발급 없이 그대로 던진다',
    async (url) => {
      setTokens({ accessToken: 'old-access', refreshToken: 'old-refresh' });
      let reissueCallCount = 0;
      server.use(
        http.post(`/api${url}`, () =>
          HttpResponse.json({ success: false, error: { code: 'AUTH_TOKEN_EXPIRED', message: '토큰이 만료되었습니다.' } }, { status: 401 }),
        ),
        http.post('/api/auth/reissue', () => {
          reissueCallCount += 1;
          return HttpResponse.json({ success: true, data: {} });
        }),
      );

      await expect(request({ method: 'POST', url })).rejects.toMatchObject({
        status: 401,
        code: 'AUTH_TOKEN_EXPIRED',
      });
      expect(reissueCallCount).toBe(0);
    },
  );

  test('재발급 뒤 재시도도 401이면 재발급을 다시 시도하지 않는다', async () => {
    setTokens({ accessToken: 'old-access', refreshToken: 'old-refresh' });
    let reissueCallCount = 0;

    server.use(
      http.get('/api/test-retry-fails', () =>
        HttpResponse.json({ success: false, error: { code: 'AUTH_TOKEN_EXPIRED', message: '토큰이 만료되었습니다.' } }, { status: 401 }),
      ),
      http.post('/api/auth/reissue', () => {
        reissueCallCount += 1;
        return HttpResponse.json({
          success: true,
          data: { accessToken: 'new-access', refreshToken: 'new-refresh', tokenType: 'Bearer', expiresIn: 3600, isNewUser: false },
        });
      }),
    );

    await expect(request({ url: '/test-retry-fails' })).rejects.toMatchObject({
      status: 401,
      code: 'AUTH_TOKEN_EXPIRED',
    });
    expect(reissueCallCount).toBe(1);
  });
});

describe('request — 파라미터 직렬화', () => {
  test('배열 파라미터는 같은 키 반복 · 사전순으로 보낸다', async () => {
    let capturedSearch = '';
    server.use(
      http.get('/api/test-params', ({ request: req }) => {
        capturedSearch = new URL(req.url).search;
        return HttpResponse.json({ success: true, data: null });
      }),
    );

    await request({ url: '/test-params', params: { riskGrade: ['SAFE', 'CAUTION'] } });

    expect(capturedSearch).toBe('?riskGrade=CAUTION&riskGrade=SAFE');
  });
});
