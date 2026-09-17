// WishlistButton 검증 — 비로그인 비활성 + 사유 문구(이슈 #91 재분석 버튼과 같은 형태), isWishlisted에
// 따른 등록 · 해제 전환과 눌렀을 때 나가는 요청을 확인한다 (이슈 #96 계획 8번 · 검증 표).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { setTokens } from '../../../session/store';
import { server } from '../../../test/msw/server';
import { WishlistButton } from './WishlistButton';

const PROPERTY_ID = 1024;

function renderButton(isWishlisted: boolean) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <WishlistButton propertyId={PROPERTY_ID} isWishlisted={isWishlisted} />
    </QueryClientProvider>,
  );
}

describe('WishlistButton', () => {
  it('비로그인이면 관심 등록 버튼이 비활성이고 사유 문구가 뜬다', () => {
    renderButton(false);

    const button = screen.getByRole('button', { name: '관심 등록' });
    expect(button).toBeDisabled();
    // 숨기지 않는다 — 버튼과 함께 화면에 그대로 보인다
    expect(button).toBeVisible();
    expect(screen.getByText('로그인하면 관심 매물로 등록할 수 있습니다.')).toBeInTheDocument();
  });

  it('isWishlisted가 거짓이면 관심 등록으로 뜨고, 누르면 등록 요청(POST)이 그 propertyId로 나간다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    let capturedBody: { propertyId: number } | null = null;
    server.use(
      http.post('/api/me/wishlist', async ({ request }) => {
        capturedBody = (await request.json()) as { propertyId: number };
        return HttpResponse.json({ success: true, data: null }, { status: 201 });
      }),
    );

    renderButton(false);

    const button = screen.getByRole('button', { name: '관심 등록' });
    expect(button).toBeEnabled();
    fireEvent.click(button);

    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect(capturedBody!.propertyId).toBe(PROPERTY_ID);
  });

  it('isWishlisted가 참이면 관심 해제로 뜨고, 누르면 해제 요청(DELETE)이 그 propertyId로 나간다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    let deleteRequestUrl: string | null = null;
    server.use(
      http.delete('/api/me/wishlist/:propertyId', ({ request }) => {
        deleteRequestUrl = request.url;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderButton(true);

    const button = screen.getByRole('button', { name: '관심 해제' });
    expect(button).toBeEnabled();
    fireEvent.click(button);

    await waitFor(() => expect(deleteRequestUrl).not.toBeNull());
    expect(deleteRequestUrl!).toContain(`/me/wishlist/${PROPERTY_ID}`);
  });
});
