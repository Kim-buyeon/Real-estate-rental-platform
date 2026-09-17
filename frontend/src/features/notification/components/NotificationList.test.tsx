// NotificationList(NOTI-05) 렌더링 검증 — 읽지 않은 수는 unreadCount를 그대로 쓰는지, 읽음 처리 ·
// 더 보기 · beforeValue/afterValue 표기가 명세대로 되는지를 확인한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { riskGradeLabel } from '../../../domain/risk';
import { formatCount } from '../../../lib/format';
import {
  NOTIFICATION_PAGE_1,
  NOTIFICATION_PAGE_2,
  REGISTRY_CHANGE_NOTIFICATION,
  RISK_CHANGE_NOTIFICATION,
  notificationHandlers,
} from '../../../test/msw/handlers/notification';
import { server } from '../../../test/msw/server';
import { NotificationList } from './NotificationList';

function renderList() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <NotificationList />
    </QueryClientProvider>,
  );
}

/** 요청 시작을 관측 — 개별/전체 읽음 처리, 더 보기 커서를 확인하는 테스트가 쓴다 */
function trackRequests() {
  const requests: Request[] = [];
  const onRequestStart = ({ request }: { request: Request }) => requests.push(request);
  server.events.on('request:start', onRequestStart);
  return {
    requests,
    countOf: (pathname: string) => requests.filter((r) => new URL(r.url).pathname === pathname).length,
    stop: () => server.events.removeListener('request:start', onRequestStart),
  };
}

describe('NotificationList', () => {
  it('읽지 않은 수는 unreadCount를 그대로 쓴다 — 목록의 isRead:false 건수(2)와 다른 unreadCount(4)가 화면에 뜬다', async () => {
    // 픽스처가 일부러 다르게 둔다: unreadCount 4, items 중 isRead:false는 2건(NotificationList.test.tsx가
    // 대상). 목록에서 세는 구현이면 여기서 '2'가 뜬다
    server.use(...notificationHandlers);
    const unreadInItems = NOTIFICATION_PAGE_1.items.filter((item) => !item.isRead).length;
    expect(unreadInItems).not.toBe(NOTIFICATION_PAGE_1.unreadCount);

    renderList();

    await waitFor(() =>
      expect(screen.getByText(formatCount(NOTIFICATION_PAGE_1.unreadCount), { selector: 'strong' })).toBeInTheDocument(),
    );
    // 목록에서 센 값(2)은 어디에도 강조 표시되지 않는다
    expect(screen.queryByText(formatCount(unreadInItems), { selector: 'strong' })).not.toBeInTheDocument();
  });

  it('개별 읽음 버튼을 누르면 읽음 처리 요청이 나가고 목록이 다시 조회된다', async () => {
    server.use(...notificationHandlers);
    const tracker = trackRequests();

    renderList();
    await waitFor(() => expect(tracker.countOf('/api/notifications')).toBe(1));

    fireEvent.click(screen.getAllByRole('button', { name: '읽음' })[0]!);

    await waitFor(() =>
      expect(
        tracker.requests.some(
          (r) => r.method === 'PATCH' && new URL(r.url).pathname === `/api/notifications/${RISK_CHANGE_NOTIFICATION.notificationId}/read`,
        ),
      ).toBe(true),
    );
    // 무효화로 목록이 다시 조회된다 — 명세 1.4 「읽음 처리는 목록 조회 경로로」
    await waitFor(() => expect(tracker.countOf('/api/notifications')).toBe(2));
    tracker.stop();
  });

  it('전체 읽음 버튼을 누르면 전체 읽음 요청이 나가고 목록이 다시 조회된다', async () => {
    server.use(...notificationHandlers);
    const tracker = trackRequests();

    renderList();
    await waitFor(() => expect(tracker.countOf('/api/notifications')).toBe(1));

    fireEvent.click(screen.getByRole('button', { name: '전체 읽음' }));

    await waitFor(() =>
      expect(tracker.requests.some((r) => r.method === 'PATCH' && new URL(r.url).pathname === '/api/notifications/read-all')).toBe(
        true,
      ),
    );
    await waitFor(() => expect(tracker.countOf('/api/notifications')).toBe(2));
    tracker.stop();
  });

  it('더 보기를 누르면 두 번째 요청의 cursor가 첫 쪽 응답의 nextCursor 그대로 전달된다', async () => {
    server.use(...notificationHandlers);
    const tracker = trackRequests();

    renderList();
    await waitFor(() => expect(screen.getByRole('button', { name: '더 보기' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));

    await waitFor(() => expect(tracker.countOf('/api/notifications')).toBe(2));
    const secondRequest = tracker.requests.filter((r) => new URL(r.url).pathname === '/api/notifications')[1]!;
    expect(new URL(secondRequest.url).searchParams.get('cursor')).toBe(NOTIFICATION_PAGE_1.nextCursor);
    // 둘째 쪽에 실린 항목이 실제로 더해졌는지도 함께 본다
    await waitFor(() => expect(screen.getByText(String(NOTIFICATION_PAGE_2.items[0]!.propertyId))).toBeInTheDocument());
    tracker.stop();
  });

  it('RISK_CHANGE는 위험 등급 문구를 거치고, REGISTRY_CHANGE는 서버 문자열을 그대로 보여준다', async () => {
    server.use(...notificationHandlers);

    renderList();

    // RISK_CHANGE — beforeValue · afterValue(CAUTION → DANGER)가 domain/risk.ts의 문구로 바뀐다
    const riskChangeText = `${riskGradeLabel('CAUTION')} → ${riskGradeLabel('DANGER')}`;
    await waitFor(() =>
      expect(screen.getByText((_, el) => el?.tagName === 'DD' && el.textContent === riskChangeText)).toBeInTheDocument(),
    );
    // 하드코딩된 등급 원문(CAUTION · DANGER)이 그대로는 뜨지 않는다 — 문구를 거쳤는지가 검증 대상이다
    expect(
      screen.queryByText(
        (_, el) => el?.tagName === 'DD' && el.textContent === `${RISK_CHANGE_NOTIFICATION.beforeValue} → ${RISK_CHANGE_NOTIFICATION.afterValue}`,
      ),
    ).not.toBeInTheDocument();

    // REGISTRY_CHANGE — 지문 요약 문자열은 서버 값 그대로 보인다(명세 1.3)
    const registryChangeText = `${REGISTRY_CHANGE_NOTIFICATION.beforeValue} → ${REGISTRY_CHANGE_NOTIFICATION.afterValue}`;
    expect(screen.getByText((_, el) => el?.tagName === 'DD' && el.textContent === registryChangeText)).toBeInTheDocument();
  });
});
