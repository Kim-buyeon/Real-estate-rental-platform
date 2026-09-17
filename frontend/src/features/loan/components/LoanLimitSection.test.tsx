// LoanLimitSection 검증 — 정상일 때 네 항목 · 최종 한도 · 결정 항목 강조, 참고 영역(스트레스 DSR ·
// DTI)이 한도 항목과 갈리는지, 무주택의 DSR·스트레스 줄 부재와 안내, 422 PROFILE_INCOMPLETE ·
// LOAN_PROPERTY_NOT_ELIGIBLE의 안내(오류 아님) 처리, 그 밖의 오류의 Alert 처리, 비로그인의 요청 미발생을
// 확인한다 (이슈 #98 계획 8번 · 검증 표). 문구 · 금액은 domain/loan.ts · lib/format.ts의 함수와 픽스처
// 값으로 확인해 서버 값이 바뀌면 테스트도 함께 틀어지게 한다. 임계값(80% · 4억 등)은 화면에도 테스트에도
// 두지 않는다 — 기준은 서버(LOAN_REGULATION)와 판정 기준 문서가 갖는다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { APPLIED_REGULATION_LABEL, appliedRegulationLabel, DTI_REFERENCE_LABEL, STRESS_DSR_LABEL } from '../../../domain/loan';
import { profileFieldLabel } from '../../../domain/user';
import { formatPercent, formatWon } from '../../../lib/format';
import { setTokens } from '../../../session/store';
import {
  LOAN_LIMIT,
  LOAN_LIMIT_NO_HOUSE,
  LOAN_PROFILE_INCOMPLETE_ERROR,
  LOAN_PROPERTY_NOT_ELIGIBLE_ERROR,
  loanHandlers,
  loanNoHouseHandlers,
  loanNotEligibleHandlers,
  loanProfileIncompleteHandlers,
} from '../../../test/msw/handlers/loan';
import { server } from '../../../test/msw/server';
import { LoanLimitSection } from './LoanLimitSection';

const PROPERTY_ID = 1024;

function login() {
  setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
}

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <LoanLimitSection propertyId={PROPERTY_ID} />
    </QueryClientProvider>,
  );
}

/** PROFILE_INCOMPLETE 안내의 링크(react-router Link)가 useHref를 쓰므로 이 케이스만 Router가 필요하다 */
function renderSectionWithRouter() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <LoanLimitSection propertyId={PROPERTY_ID} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('LoanLimitSection', () => {
  it('정상이면 네 항목(보증금 · 보증기관 상한 · DSR · 최종 한도)이 보이고 결정 항목에만 강조 배지가 붙는다', async () => {
    login();
    server.use(...loanHandlers);
    renderSection();

    await waitFor(() => expect(screen.getByText('최종 한도')).toBeInTheDocument());

    // 최종 한도 블록 — 금액과 결정 항목 문구
    const finalBlock = screen.getByText('최종 한도').closest('div')!;
    expect(within(finalBlock).getByText(formatWon(LOAN_LIMIT.finalLimit))).toBeInTheDocument();
    expect(
      within(finalBlock).getByText(appliedRegulationLabel(LOAN_LIMIT.appliedRegulation), { exact: false }),
    ).toBeInTheDocument();

    // 결정 항목이 아닌 두 줄 — 라벨로 그 줄을 찾아 금액을 확인한다 (배지가 없어 라벨 텍스트가 그대로다)
    const depositRow = screen.getByText(APPLIED_REGULATION_LABEL.DEPOSIT_RATIO).closest('div')!;
    expect(within(depositRow).getByText(formatWon(LOAN_LIMIT.depositLimit))).toBeInTheDocument();

    const guaranteeRow = screen.getByText(APPLIED_REGULATION_LABEL.GUARANTEE_CAP).closest('div')!;
    expect(within(guaranteeRow).getByText(formatWon(LOAN_LIMIT.guaranteeCapLimit))).toBeInTheDocument();

    // 결정 항목(DSR) — 배지가 라벨과 한 요소에 합쳐져 라벨 텍스트로는 못 찾으므로 배지를 거쳐 그 줄을 찾는다.
    // 「결정」 배지는 전체에서 하나뿐이어야 한다 — getByText가 여럿이면 그 자체로 실패한다
    const dsrRow = screen.getByText('결정').closest('div')!;
    expect(dsrRow).not.toBe(depositRow);
    expect(dsrRow).not.toBe(guaranteeRow);
    expect(dsrRow.textContent).toContain(appliedRegulationLabel(LOAN_LIMIT.appliedRegulation));
    expect(within(dsrRow).getByText(formatWon(LOAN_LIMIT.dsrLimit!))).toBeInTheDocument();
  });

  it('스트레스 DSR · DTI는 참고 영역에 있고 한도 항목과 갈리며 DSR·DTI 개념 차이 안내가 함께 있다', async () => {
    login();
    server.use(...loanHandlers);
    renderSection();

    await waitFor(() => expect(screen.getByText('참고 — 최종 한도에 반영하지 않은 수치')).toBeInTheDocument());

    const referenceSection = screen.getByText('참고 — 최종 한도에 반영하지 않은 수치').closest('section')!;
    expect(within(referenceSection).getByText(STRESS_DSR_LABEL)).toBeInTheDocument();
    expect(within(referenceSection).getByText(formatWon(LOAN_LIMIT.stressDsrLimit!))).toBeInTheDocument();
    expect(within(referenceSection).getByText(DTI_REFERENCE_LABEL)).toBeInTheDocument();
    expect(within(referenceSection).getByText(formatPercent(LOAN_LIMIT.dtiReference!))).toBeInTheDocument();

    // DSR·DTI 개념 차이 안내 — 기능 정의 LOAN-01이 화면 몫으로 정한 문구다
    expect(within(referenceSection).getByText(/DSR은 전체 대출의 원리금을/)).toBeInTheDocument();

    // 한도 항목(결정 목록)의 라벨은 참고 영역 안에 없다 — 나란히 두면 반영된 것으로 읽힌다는 계획의 결정
    expect(within(referenceSection).queryByText(APPLIED_REGULATION_LABEL.DEPOSIT_RATIO)).not.toBeInTheDocument();
    expect(within(referenceSection).queryByText(APPLIED_REGULATION_LABEL.GUARANTEE_CAP)).not.toBeInTheDocument();
    expect(within(referenceSection).queryByText(APPLIED_REGULATION_LABEL.DSR)).not.toBeInTheDocument();
  });

  it('무주택이면 DSR · 스트레스 줄이 없고(해당 없음으로도 나오지 않는다) 대신 왜 없는지 안내가 있다', async () => {
    login();
    server.use(...loanNoHouseHandlers);
    renderSection();

    await waitFor(() => expect(screen.getByText('최종 한도')).toBeInTheDocument());

    // DSR 기준 한도 라벨 자체가 없다 — 「해당 없음」으로도 표기하지 않는다
    expect(screen.queryByText(APPLIED_REGULATION_LABEL.DSR)).not.toBeInTheDocument();
    expect(screen.queryByText('해당 없음')).not.toBeInTheDocument();
    // 스트레스 DSR도 참고 영역에 없다 — 값이 null이다
    expect(screen.queryByText(STRESS_DSR_LABEL)).not.toBeInTheDocument();

    // 왜 없는지 안내
    expect(screen.getByText(/DSR은 주택 보유자에게 적용되는 규제/)).toBeInTheDocument();

    // 픽스처 정합 — 무주택은 DSR이 candidates에 없으므로 결정 항목이 DSR일 수 없다 (비즈니스 로직 6장)
    expect(LOAN_LIMIT_NO_HOUSE.appliedRegulation).not.toBe('DSR');

    const finalBlock = screen.getByText('최종 한도').closest('div')!;
    expect(within(finalBlock).getByText(formatWon(LOAN_LIMIT_NO_HOUSE.finalLimit))).toBeInTheDocument();
    expect(
      within(finalBlock).getByText(appliedRegulationLabel(LOAN_LIMIT_NO_HOUSE.appliedRegulation), { exact: false }),
    ).toBeInTheDocument();
  });

  it('422 PROFILE_INCOMPLETE는 오류 Alert이 아니라 안내이고 자격 정보 화면으로 가는 링크가 있다', async () => {
    login();
    server.use(...loanProfileIncompleteHandlers);
    renderSectionWithRouter();

    // 문구는 서버 error.message 그대로다
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent(LOAN_PROFILE_INCOMPLETE_ERROR.message),
    );
    // 오류 Alert(role=alert)이 아니라 안내(role=status)다
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    // 어느 항목이 비었는지 — error.field. 「미입력 항목 · 」와 라벨이 서로 다른 텍스트 노드라
    // 감싸는 문단에서 textContent로 확인한다
    expect(screen.getByText(/미입력 항목/)).toHaveTextContent(profileFieldLabel(LOAN_PROFILE_INCOMPLETE_ERROR.field));

    const link = screen.getByRole('link', { name: '자격 정보 입력' });
    expect(link).toHaveAttribute('href', '/me/profile');
  });

  it('422 LOAN_PROPERTY_NOT_ELIGIBLE도 오류가 아니라 안내로 뜬다', async () => {
    login();
    server.use(...loanNotEligibleHandlers);
    renderSection();

    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent(LOAN_PROPERTY_NOT_ELIGIBLE_ERROR.message),
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('그 밖의 오류(503)는 오류 Alert으로 뜬다', async () => {
    login();
    const message = '외부 연동 장애로 한도를 계산하지 못했습니다.';
    server.use(
      http.get('/api/loans/limit', () =>
        HttpResponse.json({ success: false, error: { code: 'EXTERNAL_API_UNAVAILABLE', message } }, { status: 503 }),
      ),
    );
    renderSection();

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(message));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('비로그인이면 한도를 요청하지 않는다', async () => {
    server.use(...loanHandlers);
    const requestedUrls: string[] = [];
    const onRequestStart = ({ request }: { request: Request }) => requestedUrls.push(request.url);
    server.events.on('request:start', onRequestStart);

    renderSection();

    // 안내만 확인하면 요청이 나가도 통과한다 — MSW 요청 수를 직접 센다
    expect(await screen.findByText(/로그인하면/)).toBeInTheDocument();
    expect(requestedUrls.filter((url) => url.includes('/loans/limit')).length).toBe(0);

    server.events.removeListener('request:start', onRequestStart);
  });
});
