// 테스트 전략 문서 1.1이 프론트 로직 테스트 대상으로 못박은 셋 중 둘 — SSE 전역 단일 연결과 재연결
// 후 목록 재조회(NOTI-03), 수신 시 쿼리 무효화 연쇄(NOTI-02 · NOTI-05)를 검증한다.
//
// 연결은 티켓 발급(POST /api/notifications/stream-ticket)을 거친 뒤에 열린다(이슈 102) — 그래서
// 연결을 기대하는 테스트는 notificationHandlers를 붙이고 렌더 직후가 아니라 waitFor로 기다린다.
//
// 무효화 확인은 invalidateQueries 스파이가 아니라 「그 쿼리가 다시 요청되는가」로 한다 —
// PropertyDetailPanel.test.tsx의 DistrictCountsProbe · WishlistProbe 선례를 같은 방식으로 따른다.
import { QueryClient, QueryClientProvider, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { render, waitFor } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';
import { notificationQueries } from '../queries/notification';
import { propertyQueries, wishlistQueries } from '../queries/property';
import { riskQueries } from '../queries/risk';
import { setTokens } from '../session/store';
import { READY_STATE_CLOSED, getEventSourceInstances, installFakeEventSource } from '../test/eventSource';
import { STREAM_TICKET_PREFIX, notificationHandlers } from '../test/msw/handlers/notification';
import { PROPERTY_DETAIL, propertyHandlers, wishlistHandlers } from '../test/msw/handlers/property';
import { riskHandlers } from '../test/msw/handlers/risk';
import { server } from '../test/msw/server';
import { NotificationStream } from './NotificationStream';

/** 위험도 · 관심 매물 픽스처가 공유하는 매물 번호 — riskHandlers · propertyHandlers는 :propertyId를
 * 무시하고 이 매물의 고정 응답을 주므로, 무효화 연쇄 테스트는 이 번호로 관측기를 마운트한다. */
const PROPERTY_ID = PROPERTY_DETAIL.propertyId;

function NotificationListProbe() {
  useInfiniteQuery(notificationQueries.list());
  return null;
}
function RiskAnalysisProbe() {
  useQuery(riskQueries.analysis(PROPERTY_ID));
  return null;
}
function RiskRegistryProbe() {
  useQuery(riskQueries.registry(PROPERTY_ID));
  return null;
}
function PropertyDetailProbe() {
  useQuery(propertyQueries.detail(PROPERTY_ID));
  return null;
}
function DistrictCountsProbe() {
  useQuery(propertyQueries.districtCounts({}));
  return null;
}
function WishlistProbe() {
  useInfiniteQuery(wishlistQueries.list());
  return null;
}

function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
}

/** 요청을 pathname으로만 추적한다 — /properties/1024와 /properties/1024/risk를 포함(includes)으로
 * 비교하면 서로 섞인다. PropertyDetailPanel.test.tsx의 requestedUrls 관측 방식을 pathname으로 좁힌다 */
function trackRequestPaths() {
  const paths: string[] = [];
  const onRequestStart = ({ request }: { request: Request }) => paths.push(new URL(request.url).pathname);
  server.events.on('request:start', onRequestStart);
  return {
    paths,
    countOf: (path: string) => paths.filter((p) => p === path).length,
    stop: () => server.events.removeListener('request:start', onRequestStart),
  };
}

const PATH = {
  streamTicket: '/api/notifications/stream-ticket',
  list: '/api/notifications',
  analysis: `/api/properties/${PROPERTY_ID}/risk`,
  registry: `/api/properties/${PROPERTY_ID}/registry`,
  detail: `/api/properties/${PROPERTY_ID}`,
  districtCounts: '/api/properties/district-counts',
  wishlist: '/api/me/wishlist',
};

describe('NotificationStream', () => {
  let restoreEventSource: (() => void) | undefined;

  afterEach(() => {
    restoreEventSource?.();
    restoreEventSource = undefined;
  });

  describe('전역 단일 연결', () => {
    it('로그인 상태에서 마운트하면 연결이 하나 열린다', async () => {
      restoreEventSource = installFakeEventSource();
      setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
      server.use(...notificationHandlers);
      const queryClient = createQueryClient();

      render(
        <QueryClientProvider client={queryClient}>
          <NotificationStream />
        </QueryClientProvider>,
      );

      await waitFor(() => expect(getEventSourceInstances()).toHaveLength(1));
    });

    it('같은 queryClient로 리렌더해도 연결이 늘지 않는다', async () => {
      restoreEventSource = installFakeEventSource();
      setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
      server.use(...notificationHandlers);
      const queryClient = createQueryClient();

      const { rerender } = render(
        <QueryClientProvider client={queryClient}>
          <NotificationStream />
        </QueryClientProvider>,
      );
      await waitFor(() => expect(getEventSourceInstances()).toHaveLength(1));

      rerender(
        <QueryClientProvider client={queryClient}>
          <NotificationStream />
        </QueryClientProvider>,
      );

      expect(getEventSourceInstances()).toHaveLength(1);
    });

    it('비로그인이면 연결하지 않는다', () => {
      restoreEventSource = installFakeEventSource();
      const queryClient = createQueryClient();

      render(
        <QueryClientProvider client={queryClient}>
          <NotificationStream />
        </QueryClientProvider>,
      );

      expect(getEventSourceInstances()).toHaveLength(0);
    });

    it('언마운트하면 close()가 불린다', async () => {
      restoreEventSource = installFakeEventSource();
      setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
      server.use(...notificationHandlers);
      const queryClient = createQueryClient();

      const { unmount } = render(
        <QueryClientProvider client={queryClient}>
          <NotificationStream />
        </QueryClientProvider>,
      );
      await waitFor(() => expect(getEventSourceInstances()).toHaveLength(1));
      const instance = getEventSourceInstances()[0]!;
      expect(instance.readyState).not.toBe(READY_STATE_CLOSED);

      unmount();

      expect(instance.readyState).toBe(READY_STATE_CLOSED);
    });
  });

  describe('수신 시 무효화 연쇄', () => {
    async function renderConnectedWithProbes() {
      restoreEventSource = installFakeEventSource();
      setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
      server.use(...notificationHandlers, ...riskHandlers, ...propertyHandlers, ...wishlistHandlers);
      const tracker = trackRequestPaths();
      const queryClient = createQueryClient();

      render(
        <QueryClientProvider client={queryClient}>
          <NotificationStream />
          <NotificationListProbe />
          <RiskAnalysisProbe />
          <RiskRegistryProbe />
          <PropertyDetailProbe />
          <DistrictCountsProbe />
          <WishlistProbe />
        </QueryClientProvider>,
      );

      // 관측기 전부의 첫 조회가 끝날 때까지 기다린다
      await waitFor(() => {
        Object.values(PATH).forEach((path) => expect(tracker.countOf(path)).toBeGreaterThanOrEqual(1));
      });

      const before = Object.fromEntries(Object.entries(PATH).map(([key, path]) => [key, tracker.countOf(path)])) as Record<
        keyof typeof PATH,
        number
      >;
      await waitFor(() => expect(getEventSourceInstances()).toHaveLength(1));
      const source = getEventSourceInstances()[0]!;
      return { tracker, source, before };
    }

    it('모든 유형 공통 — notification.list를 무효화한다', async () => {
      const { tracker, source, before } = await renderConnectedWithProbes();

      source.emit('RISK_CHANGE', {
        notificationId: 1,
        type: 'RISK_CHANGE',
        propertyId: PROPERTY_ID,
        createdAt: '2026-07-29T03:05:00+09:00',
      });

      await waitFor(() => expect(tracker.countOf(PATH.list)).toBe(before.list + 1));
      tracker.stop();
    });

    it('RISK_CHANGE — 위에 더해 risk.analysis · property 전체 · wishlist 전체를 무효화한다', async () => {
      const { tracker, source, before } = await renderConnectedWithProbes();

      source.emit('RISK_CHANGE', {
        notificationId: 1,
        type: 'RISK_CHANGE',
        propertyId: PROPERTY_ID,
        createdAt: '2026-07-29T03:05:00+09:00',
      });

      await waitFor(() => {
        expect(tracker.countOf(PATH.analysis)).toBe(before.analysis + 1);
        // property 전체(도메인 루트)의 대표로 자치구 집계를 본다 — PropertyDetailPanel.test.tsx와 같다
        expect(tracker.countOf(PATH.districtCounts)).toBe(before.districtCounts + 1);
        expect(tracker.countOf(PATH.wishlist)).toBe(before.wishlist + 1);
      });

      // 반대 단언 — risk.registry는 REGISTRY_CHANGE 전용이라 RISK_CHANGE로는 다시 요청되지 않는다
      expect(tracker.countOf(PATH.registry)).toBe(before.registry);
      tracker.stop();
    });

    it('REGISTRY_CHANGE — 위에 더해 risk.registry · risk.analysis · property.detail을 무효화한다', async () => {
      const { tracker, source, before } = await renderConnectedWithProbes();

      source.emit('REGISTRY_CHANGE', {
        notificationId: 2,
        type: 'REGISTRY_CHANGE',
        propertyId: PROPERTY_ID,
        createdAt: '2026-07-29T03:05:00+09:00',
      });

      await waitFor(() => {
        expect(tracker.countOf(PATH.registry)).toBe(before.registry + 1);
        expect(tracker.countOf(PATH.analysis)).toBe(before.analysis + 1);
        expect(tracker.countOf(PATH.detail)).toBe(before.detail + 1);
        expect(tracker.countOf(PATH.list)).toBe(before.list + 1);
      });

      // 반대 단언 — property 전체(자치구 집계) · wishlist 전체는 RISK_CHANGE 전용이라
      // REGISTRY_CHANGE로는 다시 요청되지 않는다
      expect(tracker.countOf(PATH.districtCounts)).toBe(before.districtCounts);
      expect(tracker.countOf(PATH.wishlist)).toBe(before.wishlist);
      tracker.stop();
    });
  });

  describe('연결 URL', () => {
    it('액세스 토큰이 아니라 발급받은 티켓을 싣는다', async () => {
      restoreEventSource = installFakeEventSource();
      setTokens({ accessToken: 'super-secret-access-token', refreshToken: 'refresh-token' });
      server.use(...notificationHandlers);
      const queryClient = createQueryClient();

      render(
        <QueryClientProvider client={queryClient}>
          <NotificationStream />
        </QueryClientProvider>,
      );

      await waitFor(() => expect(getEventSourceInstances()).toHaveLength(1));
      const url = new URL(getEventSourceInstances()[0]!.url, 'http://localhost');

      // 이 변경의 목적이다 — 액세스 토큰은 이름으로도 값으로도 URL에 없다 (이슈 102 · RFC 9700)
      expect(url.searchParams.get('accessToken')).toBeNull();
      expect(getEventSourceInstances()[0]!.url).not.toContain('super-secret-access-token');
      // URL에 실리는 것은 발급받은 티켓 하나뿐이다
      expect(url.searchParams.get('ticket')).toContain(STREAM_TICKET_PREFIX);
      expect([...url.searchParams.keys()]).toEqual(['ticket']);
    });
  });

  describe('재연결 후 목록 재조회', () => {
    it('CLOSED에서 error를 받으면 백오프 뒤 새 티켓으로 재연결하고 open에서 목록을 다시 조회한다', async () => {
      restoreEventSource = installFakeEventSource();
      setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
      server.use(...notificationHandlers);
      const tracker = trackRequestPaths();
      const queryClient = createQueryClient();

      render(
        <QueryClientProvider client={queryClient}>
          <NotificationStream />
          <NotificationListProbe />
        </QueryClientProvider>,
      );

      await waitFor(() => expect(getEventSourceInstances()).toHaveLength(1));
      await waitFor(() => expect(tracker.countOf(PATH.list)).toBe(1));
      expect(tracker.countOf(PATH.streamTicket)).toBe(1);
      const first = getEventSourceInstances()[0]!;

      first.readyState = READY_STATE_CLOSED;
      first.emit('error');

      // 가짜 타이머를 쓰지 않는다 — 재연결 경로가 백오프 타이머 뒤에 티켓 발급(MSW 응답)을 기다리는
      // 비동기 경로가 되어, 타이머를 가짜로 두면 그 응답이 해소되는 시점을 함께 조작해야 한다.
      // 최초 백오프는 1초라 실제 시간으로 기다려도 짧다 (app/NotificationStream.tsx backoffDelay)
      await waitFor(() => expect(getEventSourceInstances()).toHaveLength(2), { timeout: 3_000 });

      // **재연결마다 새 티켓이다** — 티켓은 1회용이라 첫 연결의 것을 다시 쓸 수 없다
      expect(tracker.countOf(PATH.streamTicket)).toBe(2);
      const second = getEventSourceInstances()[1]!;
      expect(second.url).toContain('ticket=');
      expect(second.url).not.toBe(first.url);
      expect(second.url).not.toContain('accessToken');

      second.emit('open');

      await waitFor(() => expect(tracker.countOf(PATH.list)).toBe(2));
      tracker.stop();
    });

    it('티켓 발급이 실패하면 백오프 뒤 다시 발급받아 연결한다', async () => {
      restoreEventSource = installFakeEventSource();
      setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
      // 첫 발급만 500으로 떨어뜨린다 — once 핸들러가 소진되면 정상 발급으로 돌아간다
      server.use(
        http.post(
          '/api/notifications/stream-ticket',
          () => HttpResponse.json({ success: false, error: { code: 'INTERNAL_ERROR', message: '오류' } }, { status: 500 }),
          { once: true },
        ),
        ...notificationHandlers,
      );
      const tracker = trackRequestPaths();
      const queryClient = createQueryClient();

      render(
        <QueryClientProvider client={queryClient}>
          <NotificationStream />
        </QueryClientProvider>,
      );

      await waitFor(() => expect(tracker.countOf(PATH.streamTicket)).toBe(1));
      // 첫 발급이 실패해도 연결은 없고 예외도 나지 않는다 — 실시간 실패는 기능 실패가 아니다
      expect(getEventSourceInstances()).toHaveLength(0);

      await waitFor(() => expect(getEventSourceInstances()).toHaveLength(1), { timeout: 3_000 });
      expect(tracker.countOf(PATH.streamTicket)).toBe(2);
      tracker.stop();
    });

    it('세션이 유효하지 않아(401) 발급이 실패하면 다시 시도하지 않는다', async () => {
      restoreEventSource = installFakeEventSource();
      setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
      // AUTH_TOKEN_EXPIRED가 아니므로 api/client.ts의 재발급 재시도 경로를 타지 않는다
      server.use(
        http.post('/api/notifications/stream-ticket', () =>
          HttpResponse.json({ success: false, error: { code: 'AUTH_REQUIRED', message: '인증이 필요합니다.' } }, { status: 401 }),
        ),
      );
      const tracker = trackRequestPaths();
      const queryClient = createQueryClient();

      render(
        <QueryClientProvider client={queryClient}>
          <NotificationStream />
        </QueryClientProvider>,
      );

      await waitFor(() => expect(tracker.countOf(PATH.streamTicket)).toBe(1));
      // 최초 백오프(1초)를 넘겨 기다려도 두 번째 발급이 없다 — 결과가 같은 실패에서는 멈춘다
      await new Promise((resolve) => setTimeout(resolve, 1_500));
      expect(tracker.countOf(PATH.streamTicket)).toBe(1);
      expect(getEventSourceInstances()).toHaveLength(0);
      tracker.stop();
    });
  });

  describe('실시간 실패가 기능 실패가 아니다', () => {
    it('globalThis.EventSource가 없어도 렌더가 깨지지 않는다', () => {
      expect(globalThis.EventSource).toBeUndefined();
      setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
      const queryClient = createQueryClient();

      expect(() =>
        render(
          <QueryClientProvider client={queryClient}>
            <NotificationStream />
          </QueryClientProvider>,
        ),
      ).not.toThrow();
      expect(getEventSourceInstances()).toHaveLength(0);
    });

    it('연결 오류를 받아도 예외가 나지 않고, CLOSED가 아니면 재연결하지 않는다', async () => {
      restoreEventSource = installFakeEventSource();
      setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
      server.use(...notificationHandlers);
      const queryClient = createQueryClient();

      render(
        <QueryClientProvider client={queryClient}>
          <NotificationStream />
        </QueryClientProvider>,
      );
      await waitFor(() => expect(getEventSourceInstances()).toHaveLength(1));
      const source = getEventSourceInstances()[0]!;

      // readyState는 기본값 CONNECTING(0) — 브라우저가 같은 URL로 재시도하는 중이라는 뜻이라
      // 구현은 아무것도 하지 않는다 (app/NotificationStream.tsx handleError)
      expect(() => source.emit('error')).not.toThrow();
      expect(getEventSourceInstances()).toHaveLength(1);
    });
  });
});
