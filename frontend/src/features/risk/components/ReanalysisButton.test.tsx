// ReanalysisButton 검증 — 비로그인 비활성, 재분석 성공 시 등급 변경 문구, 429 안내와 그 외 오류의
// Alert 처리를 확인한다 (이슈 #91 계획 8번 · 검증 표). 문구는 domain/risk.ts · lib/format.ts의
// 함수로 확인해 서버 값이 바뀌면 테스트도 함께 틀어지게 한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { riskGradeLabel } from '../../../domain/risk';
import { formatDateTime } from '../../../lib/format';
import { setTokens } from '../../../session/store';
import { REANALYZE_RESULT } from '../../../test/msw/handlers/risk';
import { server } from '../../../test/msw/server';
import { ReanalysisButton } from './ReanalysisButton';

const PROPERTY_ID = 1024;

function renderButton() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ReanalysisButton propertyId={PROPERTY_ID} />
    </QueryClientProvider>,
  );
}

describe('ReanalysisButton', () => {
  it('비로그인이면 재분석 버튼이 비활성이고 사유 문구가 뜬다', () => {
    renderButton();

    expect(screen.getByRole('button', { name: '재분석' })).toBeDisabled();
    expect(screen.getByText('로그인하면 재분석을 요청할 수 있습니다.')).toBeInTheDocument();
  });

  it('로그인 상태에서 누르면 재분석이 요청되고 등급이 바뀌면 이전 등급 → 새 등급 문구가 뜬다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    server.use(
      http.post('/api/properties/:propertyId/risk/reanalyze', () =>
        HttpResponse.json({ success: true, data: REANALYZE_RESULT }),
      ),
    );
    renderButton();

    const button = screen.getByRole('button', { name: '재분석' });
    expect(button).toBeEnabled();
    fireEvent.click(button);

    // REANALYZE_RESULT.gradeChanged는 참이다 — 이전 등급 → 새 등급 문구가 나온다
    const expectedText = `등급이 ${riskGradeLabel(REANALYZE_RESULT.previousGrade)}에서 ${riskGradeLabel(REANALYZE_RESULT.riskGrade)}(으)로 바뀌었습니다.`;
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(expectedText));
  });

  it('429 RISK_REANALYZE_TOO_SOON은 오류 Alert이 아니라 안내로 뜨고 retryAfter 시각이 함께 나온다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    const message = '잠시 뒤 다시 시도해 주세요.';
    const retryAfter = '2026-07-29T11:00:00+09:00';
    server.use(
      http.post('/api/properties/:propertyId/risk/reanalyze', () =>
        HttpResponse.json(
          { success: false, error: { code: 'RISK_REANALYZE_TOO_SOON', message, retryAfter } },
          { status: 429 },
        ),
      ),
    );
    renderButton();

    fireEvent.click(screen.getByRole('button', { name: '재분석' }));

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(message));
    expect(screen.getByRole('status')).toHaveTextContent(`${formatDateTime(retryAfter)}부터 가능합니다.`);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('그 밖의 오류는 오류 Alert으로 뜬다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    const message = '외부 연동 장애로 재분석하지 못했습니다.';
    server.use(
      http.post('/api/properties/:propertyId/risk/reanalyze', () =>
        HttpResponse.json({ success: false, error: { code: 'EXTERNAL_API_UNAVAILABLE', message } }, { status: 503 }),
      ),
    );
    renderButton();

    fireEvent.click(screen.getByRole('button', { name: '재분석' }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(message));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
