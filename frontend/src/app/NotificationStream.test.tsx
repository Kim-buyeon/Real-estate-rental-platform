// 테스트 전략 문서 1.1이 프론트 로직 테스트 대상으로 못박은 셋 중 둘 — SSE 전역 단일 연결과 재연결
// 후 목록 재조회(NOTI-03), 수신 시 쿼리 무효화 연쇄(NOTI-02 · NOTI-05)를 검증한다.
//
// 무효화 확인은 invalidateQueries 스파이가 아니라 「그 쿼리가 다시 요청되는가」로 한다 —
// PropertyDetailPanel.test.tsx의 DistrictCountsProbe · WishlistProbe 선례를 같은 방식으로 따른다.
import { QueryClient, QueryClientProvider, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { notificationQueries } from '../queries/notification';
import { propertyQueries, wishlistQueries } from '../queries/property';
import { riskQueries } from '../queries/risk';
import { setTokens } from '../session/store';
import { READY_STATE_CLOSED, getEventSourceInstances, installFakeEventSource } from '../test/eventSource';
import { notificationHandlers } from '../test/msw/handlers/notification';
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

  describe('재연결 후 목록 재조회', () => {
    it('CLOSED에서 error를 받으면 백오프 뒤 그 시점의 토큰으로 재연결하고 open에서 목록을 다시 조회한다', async () => {
      restoreEventSource = installFakeEventSource();
      setTokens({ accessToken: 'token-1', refreshToken: 'refresh-token' });
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

      const first = getEventSourceInstances()[0]!;
      expect(first.url).toContain('accessToken=token-1');

      vi.useFakeTimers();
      try {
        // 재발급이 그 사이 일어난 것처럼 토큰이 바뀐다 — 재연결은 이 시점의 토큰을 써야 한다
        setTokens({ accessToken: 'token-2', refreshToken: 'refresh-token' });
        first.readyState = READY_STATE_CLOSED;
        first.emit('error');

        await act(async () => {
          await vi.advanceTimersByTimeAsync(1_000); // 백오프 1초(재연결 최초 지연)
        });
      } finally {
        vi.useRealTimers();
      }

      expect(getEventSourceInstances()).toHaveLength(2);
      const second = getEventSourceInstances()[1]!;
      expect(second.url).toContain('accessToken=token-2');
      expect(second.url).not.toContain('token-1');

      second.emit('open');

      await waitFor(() => expect(tracker.countOf(PATH.list)).toBe(2));
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
