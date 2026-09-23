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
import { afterEach, describe, expect, it, vi } from 'vitest';
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

/** setTimeout 가로채기가 쓰는 호출 형태. 표준 선언은 환경(jsdom · @types/node)마다 달라 여기서
 * 필요한 만큼만 다시 적고, 전역에 넣을 때는 test/eventSource.ts와 같이 unknown을 거쳐 캐스팅한다 */
type TimerCallback = (...callbackArgs: unknown[]) => void;
type SetTimeoutLike = (handler: TimerCallback, ms?: number, ...args: unknown[]) => number;

/**
 * 재연결 타이머가 잡은 지연을 관찰하는 수단.
 *
 * **backoffDelay를 직접 부르지 않는다.** app/은 main.tsx 외에 아무도 import하지 않는 조합 루트라
 * 테스트만 쓰는 export를 늘릴 자리가 아니고, 실제로 이 파일에 함수 export를 더하면
 * react-refresh/only-export-components가 막는다 — eslint.config.js의 reactRefresh.configs.vite가
 * 이 규칙을 error로 둔다(「Use a new file to share constants or functions between components」).
 * 그래서 계산값이 아니라 **재연결을 몇 ms 뒤로 잡았는가**를 본다. 관측 대상은 구현의 관측 가능한
 * 동작이므로 이쪽이 더 정확하기도 하다.
 *
 * 가로챈 백오프 타이머는 0ms로 바꿔 곧바로 실행한다 — 실제로 1s · 2s · 4s를 기다리면 상한(30s)에
 * 이르는 회차까지 볼 수 없다. 그보다 짧은 타이머(React · MSW)는 지연을 바꾸지 않고 흘려보낸다.
 */
function captureBackoffDelays() {
  const delays: number[] = [];
  const original = globalThis.setTimeout;
  const realSetTimeout = original as unknown as SetTimeoutLike;

  globalThis.setTimeout = ((handler: TimerCallback, ms?: number, ...args: unknown[]) => {
    if (typeof ms === 'number' && ms >= MIN_BACKOFF_DELAY_MS && ms <= MAX_BACKOFF_DELAY_MS) {
      delays.push(ms);
      return realSetTimeout(handler, 0, ...args);
    }
    return realSetTimeout(handler, ms, ...args);
  }) as unknown as typeof globalThis.setTimeout;

  return {
    delays,
    /**
     * waitFor를 쓰지 않는다 — RTL의 대기는 자기 시계를 1초짜리 setTimeout으로 걸어 관측 대상과
     * 섞인다. 이 대기는 가로채기 전의 setTimeout을 직접 써서 관측에 잡히지 않는다.
     */
    waitForDelays: async (count: number) => {
      const deadline = Date.now() + 8_000;
      while (delays.length < count) {
        if (Date.now() > deadline) {
          throw new Error(`백오프 지연 ${count}건을 기다리다 시간이 지났다 (관측 ${delays.length}건)`);
        }
        await new Promise((resolve) => realSetTimeout(resolve as TimerCallback, 5));
      }
    },
    restore: () => {
      globalThis.setTimeout = original;
    },
  };
}

/**
 * 백오프 상한 — app/NotificationStream.tsx의 RECONNECT_BASE_DELAY_MS(1s) · RECONNECT_MAX_DELAY_MS(30s)로
 * 만들어지는 지수 수열이다. 구현이 내보내지 않는 값이라 여기서 같은 식으로 다시 세운다. 지터가 붙은
 * 실제 지연은 이 상한의 절반과 상한 사이에 놓인다(equal jitter).
 */
const MAX_BACKOFF_DELAY_MS = 30_000;
/** 가능한 가장 짧은 백오프 = 첫 상한(1s)의 절반. 관측에서 다른 타이머와 갈라내는 하한이다 */
const MIN_BACKOFF_DELAY_MS = 500;
const backoffCeiling = (retryCount: number) => Math.min(1_000 * 2 ** retryCount, MAX_BACKOFF_DELAY_MS);

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
          HttpResponse.json({ success: false, error: { code: 'AUTH_INVALID_CREDENTIAL', message: '인증이 필요합니다.' } }, { status: 401 }),
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

  /**
   * 재연결 지연에 지터가 없으면 모든 브라우저의 재시도 시각이 한 점으로 수렴한다 — 앱이 죽었다
   * 살아나는 가장 약한 순간에 티켓 발급 POST와 연결이 한꺼번에 몰린다(이슈 168). equal jitter를
   * 썼으므로 지연은 지수 백오프 상한의 **절반과 상한 사이**에 흩어져야 한다.
   *
   * 관측은 티켓 발급을 계속 실패시켜 얻는다 — 500은 401 · 403이 아니라 멈추지 않고(handleIssueFailure)
   * 매번 백오프 타이머를 새로 잡으므로, 한 번의 마운트에서 회차별 지연을 차례로 볼 수 있다.
   */
  describe('재연결 백오프 지터', () => {
    let restoreTimers: (() => void) | undefined;

    afterEach(() => {
      restoreTimers?.();
      restoreTimers = undefined;
      vi.restoreAllMocks();
    });

    /** 티켓 발급이 계속 실패하는 동안 잡힌 백오프 지연을 회차 순서대로 모은다 */
    async function collectBackoffDelays(count: number): Promise<number[]> {
      // 구현은 EventSource가 없으면 티켓을 발급받기도 전에 돌아선다 — 재연결 경로를 태우려면 필요하다.
      // 발급이 늘 실패하므로 연결 자체는 한 번도 열리지 않는다
      restoreEventSource = installFakeEventSource();
      setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
      server.use(
        http.post('/api/notifications/stream-ticket', () =>
          HttpResponse.json({ success: false, error: { code: 'INTERNAL_ERROR', message: '오류' } }, { status: 500 }),
        ),
      );
      const timers = captureBackoffDelays();
      restoreTimers = timers.restore;

      const { unmount } = render(
        <QueryClientProvider client={createQueryClient()}>
          <NotificationStream />
        </QueryClientProvider>,
      );

      try {
        await timers.waitForDelays(count);
      } finally {
        // 재시도는 상한 횟수가 없다(서버가 복구되면 붙어야 한다) — 멈추는 것은 언마운트뿐이다
        unmount();
      }
      expect(getEventSourceInstances()).toHaveLength(0);
      // 기다림이 풀린 뒤 언마운트까지 한 회차가 더 돌 수 있다 — 관측은 요청한 회차까지만 본다
      return timers.delays.slice(0, count);
    }

    /** 회차를 모으는 테스트의 상한. 회차마다 티켓 발급 왕복이 한 번씩이라 기본 5초보다 넉넉히 둔다 —
     * 관측이 서지 않을 때 vitest의 기본 시계보다 waitForDelays의 안내가 먼저 나오게 하려는 것이기도 하다 */
    const COLLECT_TIMEOUT_MS = 15_000;

    it('난수가 0이면 지연이 상한의 절반이다 — 고정분은 항상 남는다', async () => {
      vi.spyOn(Math, 'random').mockReturnValue(0);

      const delays = await collectBackoffDelays(3);

      // full jitter였다면 여기가 0에 가까워져 끊기자마자 되치는 경로가 생긴다
      expect(delays).toEqual([500, 1_000, 2_000]);
    }, COLLECT_TIMEOUT_MS);

    it('난수가 0.5면 지연이 상한의 4분의 3이다', async () => {
      vi.spyOn(Math, 'random').mockReturnValue(0.5);

      const delays = await collectBackoffDelays(3);

      expect(delays).toEqual([750, 1_500, 3_000]);
    }, COLLECT_TIMEOUT_MS);

    it('난수가 1에 가까워도 상한(30초)을 넘지 않고, 회차가 커지면 상한에서 멈춘다', async () => {
      // Math.random()은 [0, 1)이라 1은 나오지 않는다 — 경계 바로 아래를 넣는다
      vi.spyOn(Math, 'random').mockReturnValue(0.999_999);

      const delays = await collectBackoffDelays(8);

      delays.forEach((delay, retryCount) => {
        const ceiling = backoffCeiling(retryCount);
        expect(delay).toBeLessThan(ceiling);
        expect(delay).toBeGreaterThan(ceiling * 0.99);
      });
      // 상한에서 멈춘다 — retryCount 5부터 상한이 30초라 회차가 더 커져도 지연이 자라지 않는다
      expect(Math.max(...delays)).toBeLessThanOrEqual(MAX_BACKOFF_DELAY_MS);
      expect(delays.slice(5)).toHaveLength(3);
      delays.slice(5).forEach((delay) => expect(delay).toBeGreaterThan(29_000));
    }, COLLECT_TIMEOUT_MS);

    it('난수를 그대로 두면 지연이 회차마다 [상한/2, 상한] 구간 안에 있다', async () => {
      const delays = await collectBackoffDelays(8);

      delays.forEach((delay, retryCount) => {
        const ceiling = backoffCeiling(retryCount);
        expect(delay).toBeGreaterThanOrEqual(ceiling / 2);
        expect(delay).toBeLessThanOrEqual(ceiling);
      });
    }, COLLECT_TIMEOUT_MS);

    it('같은 회차에서도 값이 갈린다 — 결정론적이 아니다', async () => {
      const delays = await collectBackoffDelays(8);

      // retryCount 5 · 6 · 7은 상한이 모두 30초로 같다. 지터가 없으면 셋이 같은 값이었을 자리다
      const atCeiling = delays.slice(5);
      expect(atCeiling).toHaveLength(3);
      expect(new Set(atCeiling).size).toBeGreaterThan(1);
    }, COLLECT_TIMEOUT_MS);
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
