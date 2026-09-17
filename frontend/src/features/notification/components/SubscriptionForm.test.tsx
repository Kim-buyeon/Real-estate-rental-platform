// SubscriptionForm 검증 — 이 슬라이스의 핵심인 「enabled가 false여도 조건은 그대로 전송된다」(명세
// 1.2), 조건 입력이 꺼진 상태에서도 감춰지지 않는지, 네 항목 전체가 PUT에 담기는지, 선택 필드가
// 빈 값이면 키 자체가 빠지는지, 서버 400 error.field로 자치구 입력을 고르는지, wishlistMonitoring
// 안내 노출, 저장 성공 후 재조회를 확인한다 (이슈 #108 계획).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { NotificationSubscriptions } from '../../../api/notification';
import { SUBSCRIPTION_ITEM_HINT, SUBSCRIPTION_ITEMS } from '../../../domain/notification';
import { SUBSCRIPTIONS_FIXTURE, subscriptionHandlers } from '../../../test/msw/handlers/notification';
import { server } from '../../../test/msw/server';
import { SubscriptionForm } from './SubscriptionForm';

function renderForm() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <SubscriptionForm />
    </QueryClientProvider>,
  );
}

/** 폼이 그려졌다는 신호로 자치구 체크박스 묶음이 뜨는 것을 쓴다 */
async function waitForLoaded() {
  return screen.findByRole('group', { name: '자치구' });
}

describe('SubscriptionForm', () => {
  it('꺼진 항목(newProperty.enabled: false)의 조건이 그대로 PUT에 담긴다', async () => {
    let capturedBody: NotificationSubscriptions | null = null;
    server.use(
      http.get('/api/me/notification-subscriptions', () =>
        HttpResponse.json({ success: true, data: SUBSCRIPTIONS_FIXTURE }),
      ),
      http.put('/api/me/notification-subscriptions', async ({ request }) => {
        capturedBody = (await request.json()) as NotificationSubscriptions;
        return HttpResponse.json({ success: true, data: SUBSCRIPTIONS_FIXTURE });
      }),
    );

    renderForm();
    await waitForLoaded();

    // 아무것도 바꾸지 않고 저장한다
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(capturedBody).not.toBeNull());
    const body = capturedBody!;

    expect(body.newProperty.enabled).toBe(false);
    expect(body.newProperty.conditions.districts).toEqual(SUBSCRIPTIONS_FIXTURE.newProperty.conditions.districts);
  });

  it('수신을 꺼도 자치구 조건 입력이 감춰지거나 비활성화되지 않는다', async () => {
    server.use(...subscriptionHandlers);
    renderForm();
    const group = await waitForLoaded();

    // enabled가 false인 픽스처인데도 조건 입력이 보이고 조작 가능하다
    expect(group).toBeVisible();
    SUBSCRIPTIONS_FIXTURE.newProperty.conditions.districts.forEach((district) => {
      const checkbox = screen.getByRole('checkbox', { name: district });
      expect(checkbox).toBeVisible();
      expect(checkbox).not.toBeDisabled();
      expect(checkbox).toBeChecked();
    });

    // 조작도 가능하다 — 체크를 해제하면 반영된다
    const [firstDistrict] = SUBSCRIPTIONS_FIXTURE.newProperty.conditions.districts;
    const checkbox = screen.getByRole('checkbox', { name: firstDistrict });
    fireEvent.click(checkbox);
    expect(checkbox).not.toBeChecked();
  });

  it('저장 시 PUT 본문에 네 항목(newProperty · rateChange · wishlistMonitoring · consultSchedule)이 모두 담긴다', async () => {
    let capturedBody: NotificationSubscriptions | null = null;
    server.use(
      http.get('/api/me/notification-subscriptions', () =>
        HttpResponse.json({ success: true, data: SUBSCRIPTIONS_FIXTURE }),
      ),
      http.put('/api/me/notification-subscriptions', async ({ request }) => {
        capturedBody = (await request.json()) as NotificationSubscriptions;
        return HttpResponse.json({ success: true, data: SUBSCRIPTIONS_FIXTURE });
      }),
    );

    renderForm();
    await waitForLoaded();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect(Object.keys(capturedBody!).sort()).toEqual([...SUBSCRIPTION_ITEMS].sort());
  });

  it('계약 유형 · 보증금 상한을 고르지 않으면 PUT 본문에 그 키가 없다', async () => {
    let capturedBody: NotificationSubscriptions | null = null;
    server.use(
      // contractType · depositMax가 null인 픽스처 — 화면이 빈 값으로 채운다
      http.get('/api/me/notification-subscriptions', () =>
        HttpResponse.json({ success: true, data: SUBSCRIPTIONS_FIXTURE }),
      ),
      http.put('/api/me/notification-subscriptions', async ({ request }) => {
        capturedBody = (await request.json()) as NotificationSubscriptions;
        return HttpResponse.json({ success: true, data: SUBSCRIPTIONS_FIXTURE });
      }),
    );

    renderForm();
    await waitForLoaded();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect(capturedBody!.newProperty.conditions).not.toHaveProperty('contractType');
    expect(capturedBody!.newProperty.conditions).not.toHaveProperty('depositMax');
  });

  it('서버 400의 error.field(newProperty.conditions.districts)로 자치구 입력에 오류가 붙는다', async () => {
    const message = '자치구를 1개 이상 선택해 주세요.';
    server.use(
      http.get('/api/me/notification-subscriptions', () =>
        HttpResponse.json({ success: true, data: SUBSCRIPTIONS_FIXTURE }),
      ),
      http.put('/api/me/notification-subscriptions', () =>
        HttpResponse.json(
          {
            success: false,
            error: { code: 'INVALID_REQUEST', message, field: 'newProperty.conditions.districts' },
          },
          { status: 400 },
        ),
      ),
    );

    renderForm();
    await waitForLoaded();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await screen.findByText(message);

    // 폼 위 Alert가 아니라 자치구 입력에 붙는다 — 자치구 묶음의 aria-describedby가 가리키는 오류
    // 문단에 문구가 있고, role=alert가 그 하나뿐이다(폼 위 Alert가 별도로 뜨지 않는다)
    const group = screen.getByRole('group', { name: '자치구' });
    const describedBy = group.getAttribute('aria-describedby') ?? '';
    const errorId = describedBy.split(' ').find((id) => id.endsWith('-error'));
    expect(errorId).toBeDefined();
    expect(document.getElementById(errorId!)).toHaveTextContent(message);
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  it('관심 매물 모니터링 항목에 SUBSCRIPTION_ITEM_HINT의 안내가 보인다', async () => {
    server.use(...subscriptionHandlers);
    renderForm();
    await waitForLoaded();

    expect(screen.getByText(SUBSCRIPTION_ITEM_HINT.wishlistMonitoring!)).toBeInTheDocument();
  });

  it('저장 성공 후 구독 설정 쿼리가 다시 조회된다', async () => {
    server.use(...subscriptionHandlers);

    let getCount = 0;
    const onRequestStart = ({ request }: { request: Request }) => {
      if (request.method === 'GET' && request.url.includes('/me/notification-subscriptions')) getCount += 1;
    };
    server.events.on('request:start', onRequestStart);

    renderForm();
    await waitForLoaded();
    await waitFor(() => expect(getCount).toBe(1));

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(getCount).toBe(2));

    server.events.removeListener('request:start', onRequestStart);
  });
});
