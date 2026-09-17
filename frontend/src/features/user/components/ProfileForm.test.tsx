// ProfileForm 검증 — 미입력 안내, 수정 불가 필드(이메일 · 권한 · 가입일시), 제출 형태(수정 가능
// 필드 전부를 PUT으로), 빈 입력의 0 전송, 저장 성공 후 무효화 연쇄와 그 뒤 화면의 출처가 캐시로
// 돌아오는지(입력 초안 비움 · 저장 안내 정리)를 확인한다 (이슈 #94 계획 7번 ·
// 검증 표). loan 무효화는 LOAN-01이 아직 없어 보지 않는다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { Profile } from '../../../api/user';
import { PROFILE_FIELD_LABEL, PROFILE_FIELDS, profileFieldLabel, roleLabel } from '../../../domain/user';
import { formatDateTime } from '../../../lib/format';
import { PROFILE, PROFILE_INCOMPLETE, userHandlers } from '../../../test/msw/handlers/user';
import { server } from '../../../test/msw/server';
import { ProfileForm } from './ProfileForm';

function renderForm() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ProfileForm />
    </QueryClientProvider>,
  );
}

/** 계정 정보 블록이 그려졌다는 신호로 이메일(고정 픽스처 값)이 뜨는 것을 쓴다 */
async function waitForLoaded(email: string) {
  await waitFor(() => expect(screen.getByText(email)).toBeInTheDocument());
}

interface CapturedProfileRequest {
  account: Record<string, unknown>;
  profile: Record<string, unknown>;
}

describe('ProfileForm', () => {
  it('missingFields에 담긴 항목이 표시되고 무엇이 제한되는지 안내가 함께 뜬다', async () => {
    server.use(
      http.get('/api/me/profile', () => HttpResponse.json({ success: true, data: PROFILE_INCOMPLETE })),
      http.put('/api/me/profile', () => HttpResponse.json({ success: true, data: PROFILE_INCOMPLETE })),
    );

    renderForm();
    await waitForLoaded(PROFILE_INCOMPLETE.account.email);

    const alert = screen.getByRole('status');
    PROFILE_INCOMPLETE.missingFields.forEach((field) => {
      expect(within(alert).getByText(profileFieldLabel(field))).toBeInTheDocument();
    });
    // 무엇이 제한되는지(대출 한도 계산 등)에 대한 안내가 같은 알림에 함께 있다
    expect(alert).toHaveTextContent('제한');
  });

  it('missingFields가 빈 배열이면 값이 0인 자격 정보 필드가 있어도 미입력 안내가 뜨지 않는다', async () => {
    // 화면이 값 0을 보고 스스로 판정하면 이 픽스처(existingLoan · existingLoanAnnualPayment가 0)에서도
    // 안내가 뜬다 — 이 전제가 픽스처와 실제로 맞는지 먼저 확인한다
    expect(PROFILE.missingFields).toEqual([]);
    expect(PROFILE.profile.existingLoan).toBe(0);
    expect(PROFILE.profile.existingLoanAnnualPayment).toBe(0);

    server.use(...userHandlers);
    renderForm();
    await waitForLoaded(PROFILE.account.email);

    // 미입력 안내 · 저장 성공 안내 모두 role=status다 — 아직 제출하지 않았으므로 없어야 한다
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('이메일 · 권한 · 가입일시는 화면에 보이지만 그 값을 가진 입력 요소가 없다', async () => {
    server.use(...userHandlers);
    renderForm();
    await waitForLoaded(PROFILE.account.email);

    const role = roleLabel(PROFILE.account.role);
    const createdAt = formatDateTime(PROFILE.account.createdAt);

    // 보이되
    expect(screen.getByText(PROFILE.account.email)).toBeInTheDocument();
    expect(screen.getByText(role)).toBeInTheDocument();
    expect(screen.getByText(createdAt)).toBeInTheDocument();

    // 동시에 그 값을 가진 입력 요소(input · select 등 폼 컨트롤 전체를 대상으로 한다)가 없다
    expect(screen.queryByDisplayValue(PROFILE.account.email)).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue(role)).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue(createdAt)).not.toBeInTheDocument();
  });

  it('저장하면 수정 가능한 계정 · 자격 정보 필드가 모두 담기고 수정 불가 필드는 섞이지 않은 채 PUT으로 나간다', async () => {
    let capturedBody: CapturedProfileRequest | null = null;
    server.use(
      http.get('/api/me/profile', () => HttpResponse.json({ success: true, data: PROFILE })),
      http.put('/api/me/profile', async ({ request }) => {
        capturedBody = (await request.json()) as CapturedProfileRequest;
        return HttpResponse.json({ success: true, data: PROFILE });
      }),
    );

    renderForm();
    await waitForLoaded(PROFILE.account.email);

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(capturedBody).not.toBeNull());
    const body = capturedBody!;

    // account — 수정 가능한 두 필드만
    expect(Object.keys(body.account).sort()).toEqual(['name', 'phone']);
    // profile — 수정 가능한 여섯 필드가 모두
    expect(Object.keys(body.profile).sort()).toEqual([...PROFILE_FIELDS].sort());
    // 수정 불가 필드(이메일 · 권한 · 가입일시)가 요청에 섞이지 않는다
    expect(body.account).not.toHaveProperty('email');
    expect(body.account).not.toHaveProperty('role');
    expect(body.account).not.toHaveProperty('createdAt');
  });

  it('금액 입력을 비우고 제출하면 그 필드가 빠지지 않고 0으로 담긴다', async () => {
    let capturedBody: CapturedProfileRequest | null = null;
    server.use(
      http.get('/api/me/profile', () => HttpResponse.json({ success: true, data: PROFILE })),
      http.put('/api/me/profile', async ({ request }) => {
        capturedBody = (await request.json()) as CapturedProfileRequest;
        return HttpResponse.json({ success: true, data: PROFILE });
      }),
    );

    renderForm();
    await waitForLoaded(PROFILE.account.email);

    const annualIncomeInput = screen.getByLabelText(PROFILE_FIELD_LABEL.annualIncome);
    fireEvent.change(annualIncomeInput, { target: { value: '' } });

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(capturedBody).not.toBeNull());
    // 필드가 빠지지 않고 존재하며 값이 0이다
    expect(capturedBody!.profile).toHaveProperty('annualIncome', 0);
  });

  it('저장 뒤 재조회된 값이 입력에 보인다 — 입력 초안이 캐시를 계속 가리지 않는다', async () => {
    // 서버가 저장하며 값을 다듬을 수 있다(여기서는 이름 · 연 소득). 저장 뒤 화면의 출처가 입력 초안이
    // 아니라 캐시로 돌아와야 그 값이 보인다
    const SAVED: Profile = {
      ...PROFILE,
      account: { ...PROFILE.account, name: '김철수' },
      profile: { ...PROFILE.profile, annualIncome: 12345678 },
    };
    let isSaved = false;
    server.use(
      // 저장 뒤 재조회를 늦춘다 — 재조회를 기다리지 않고 성공을 내면 옛 값이 먼저 보인다(깜빡임).
      // 늦추지 않으면 두 경우가 같은 틱에 끝나 아래 단언이 차이를 보지 못한다
      http.get('/api/me/profile', async () => {
        if (isSaved) await delay(50);
        return HttpResponse.json({ success: true, data: isSaved ? SAVED : PROFILE });
      }),
      http.put('/api/me/profile', () => {
        isSaved = true;
        return HttpResponse.json({ success: true, data: SAVED });
      }),
    );

    renderForm();
    await waitForLoaded(PROFILE.account.email);

    const nameInput = screen.getByLabelText('이름');
    const annualIncomeInput = screen.getByLabelText(PROFILE_FIELD_LABEL.annualIncome);
    fireEvent.change(nameInput, { target: { value: '입력한 이름' } });
    fireEvent.change(annualIncomeInput, { target: { value: '1' } });
    expect(nameInput).toHaveValue('입력한 이름');

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    // 저장 안내가 뜬 그 시점에 이미 재조회 값이다 — 옛 캐시 값이 한 번 스쳤다 바뀌지 않는다(깜빡임).
    // 손댄 필드(이름)와 손대지 않은 필드(연 소득)가 함께 바뀌므로 초안 전체가 비워진 것이다
    await screen.findByText('저장했습니다.');
    expect(nameInput).toHaveValue(SAVED.account.name);
    expect(annualIncomeInput).toHaveValue(SAVED.profile.annualIncome);
  });

  it('저장 성공 뒤 다시 입력하면 저장 안내가 사라진다', async () => {
    server.use(...userHandlers);
    renderForm();
    await waitForLoaded(PROFILE.account.email);

    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await screen.findByText('저장했습니다.');

    // 저장되지 않은 값 위에 성공 안내가 남지 않는다
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: '고친 이름' } });
    expect(screen.queryByText('저장했습니다.')).not.toBeInTheDocument();
  });

  it('저장 성공 후 프로필 쿼리가 다시 조회된다', async () => {
    server.use(...userHandlers);

    let profileGetCount = 0;
    const onRequestStart = ({ request }: { request: Request }) => {
      if (request.method === 'GET' && request.url.includes('/me/profile')) profileGetCount += 1;
    };
    server.events.on('request:start', onRequestStart);

    renderForm();
    await waitForLoaded(PROFILE.account.email);
    await waitFor(() => expect(profileGetCount).toBe(1));

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(profileGetCount).toBe(2));

    server.events.removeListener('request:start', onRequestStart);
  });
});
