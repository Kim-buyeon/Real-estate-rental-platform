// NOTI-03 실시간 알림 수신. EventSource를 여는 곳은 여기 하나다 — 규칙은 알림 전달 문서 1.2가 정하고
// frontend/CLAUDE.md 「알림 수신」 절이 코드 자리로 옮긴 것이다. 화면을 그리지 않는다.
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { ApiError } from '../api/client';
import { issueStreamTicket } from '../api/notification';
import { NOTIFICATION_TYPES, type NotificationType } from '../domain/notification';
import { notificationQueries } from '../queries/notification';
import { propertyQueries, wishlistQueries } from '../queries/property';
import { riskQueries } from '../queries/risk';
import { getAccessToken } from '../session/store';

/** 재연결 백오프 — 1초에서 시작해 두 배씩, 30초에서 멈춘다 */
const RECONNECT_BASE_DELAY_MS = 1_000;
const RECONNECT_MAX_DELAY_MS = 30_000;

/** EventSource.CLOSED의 값. 정적 속성을 읽지 않는다 — 테스트가 주입하는 구현에도 이 값은 같다 */
const READY_STATE_CLOSED = 2;

/**
 * 연결 URL을 만드는 함수는 하나다. **일회용 티켓 외의 것을 URL에 넣지 않는다 — 액세스 토큰은
 * 특히 넣지 않는다.**
 *
 * EventSource는 헤더를 붙일 수 없어 연결 자격을 URL로 넘겨야 한다. 액세스 토큰을 그대로 넘기면
 * 프록시 · 서버 접근 로그 · 브라우저 이력에 30분짜리 토큰이 남는다 — RFC 9700이 MUST NOT으로
 * 규정한 방식이다(이슈 102). URL에 실리는 값을 짧은 수명의 일회용 티켓으로 바꿨다 (명세 1.1).
 */
const streamUrl = (ticket: string) =>
  `/api/notifications/stream?${new URLSearchParams({ ticket }).toString()}`;

/**
 * 로그인 상태에서만 마운트된다 (app/AppShell.tsx). 로그아웃 · 언마운트에서 연결을 닫는다.
 * 화면 단위로 연결을 만들지 않는다 — 애플리케이션 전역에 하나다 (알림 전달 문서 1.2 · 명세 1.1).
 */
export function NotificationStream() {
  const queryClient = useQueryClient();

  useEffect(() => {
    let source: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    let retryCount = 0;
    let isStopped = false;
    /** 발급 응답을 기다리는 중인가. 기다리는 동안 두 번째 연결을 시작하지 않는다 */
    let isConnecting = false;

    // 수신하면 무효화만 한다 — 이벤트 본문을 화면 상태에 넣지 않는다. 본문은 목록 조회가 가져온다
    // (알림 전달 문서 1.2). 본문에서 읽는 것은 무효화 대상을 정하는 propertyId뿐이다.
    const listenerByType: Record<NotificationType, (event: Event) => void> = {
      RISK_CHANGE: (event) => invalidateForEvent(queryClient, 'RISK_CHANGE', readPropertyId(event)),
      REGISTRY_CHANGE: (event) => invalidateForEvent(queryClient, 'REGISTRY_CHANGE', readPropertyId(event)),
    };

    const handleOpen = () => {
      retryCount = 0;
      // 끊긴 사이에 쌓인 알림을 가져온다 — 무효화 연쇄 「SSE 재연결 성공」. 첫 연결에서도 무해하다
      void queryClient.invalidateQueries({ queryKey: notificationQueries.list().queryKey });
    };

    const handleError = () => {
      // 실시간 전달 실패는 기능 실패가 아니다 (알림 전달 문서 1.2) — 화면에 오류로 띄우지 않고,
      // 목록 조회가 완전한 확인 수단으로 남는다. 연결만 다시 만든다.
      if (isStopped || source === null) return;
      // CONNECTING이면 브라우저가 같은 URL로 다시 시도하는 중이다. 서버가 닫은 뒤(CLOSED)의 자동
      // 재연결에는 기대지 않는다 — 브라우저는 같은 URL을 그대로 다시 보내는데 티켓은 1회용이라
      // 이미 소비되어 거부된다. 새 티켓을 받아 새 URL로 연결한다.
      if (source.readyState !== READY_STATE_CLOSED) return;
      disconnect();
      scheduleReconnect();
    };

    /**
     * 티켓 발급이 실패했을 때. 연결 오류와 같은 백오프로 다시 시도한다 — 실시간 전달 실패는 기능
     * 실패가 아니라(알림 전달 문서 1.2) 화면에 오류로 띄우지 않고, 그 사이의 알림은 목록 조회가
     * 가져온다.
     *
     * **무한 루프가 되지 않는 근거는 둘이다.** 첫째, 재시도는 항상 백오프 타이머를 거쳐 1s · 2s ·
     * 4s … 30s로 벌어진다 — 즉시 다시 부르는 경로가 없어 요청이 몰아치지 않는다. 둘째, **다시
     * 시도해도 결과가 같은 실패에서는 멈춘다** — 401 · 403은 세션이 유효하지 않다는 뜻이고
     * (api/client.ts가 만료 토큰 재발급을 이미 한 번 시도한 뒤다), 이 상태로 다시 발급받을 수는
     * 없다. 다시 로그인하면 이 컴포넌트가 새로 마운트되며 연결한다.
     */
    const handleIssueFailure = (error: unknown) => {
      const status = error instanceof ApiError ? error.status : null;
      if (status === 401 || status === 403) return;
      scheduleReconnect();
    };

    /** 백오프 뒤 다시 연결한다. 그 자리에서도 티켓 발급이 먼저다 — connect()가 매번 발급한다 */
    const scheduleReconnect = () => {
      if (isStopped) return;
      reconnectTimer = setTimeout(() => void connect(), backoffDelay(retryCount));
      retryCount += 1;
    };

    /**
     * 연결마다 새 티켓을 받는다 — 티켓은 1회용이라 재사용할 수 없다. 재연결도 이 함수를 거치므로
     * 매번 새 티켓이다. **받은 티켓은 URL에 쓰고 버린다 — 어디에도 저장하지 않는다.**
     *
     * 발급은 queries/를 거치지 않고 api/ 함수를 직접 부른다. 캐시에 남으면 안 되는 1회용 값이라
     * 쿼리가 아니고, 여기는 app/ 조합 루트다 (frontend/CLAUDE.md import 방향).
     */
    const connect = async () => {
      if (isStopped || isConnecting || source !== null) return;
      // 세션이 비었으면 열지 않는다. 다시 로그인하면 이 컴포넌트가 새로 마운트되며 연결한다
      if (getAccessToken() === null) return;

      // EventSource는 연결 시점에 globalThis로 읽는다 — 모듈 최상위에서 참조를 잡아 두면
      // 테스트가 전역에 주입한 것이 쓰이지 않는다 (frontend/CLAUDE.md 알림 수신)
      const EventSourceCtor = globalThis.EventSource as typeof EventSource | undefined;
      // 없는 환경(주입 전 테스트 등)에서는 연결하지 않는다 — 실시간 실패는 기능 실패가 아니다.
      // 티켓을 받기 전에 본다 — 쓰지도 못할 티켓을 발급받아 버리지 않는다
      if (EventSourceCtor === undefined) return;

      isConnecting = true;
      let ticket: string;
      try {
        ticket = (await issueStreamTicket()).ticket;
      } catch (error) {
        handleIssueFailure(error);
        return;
      } finally {
        isConnecting = false;
      }
      // 기다리는 사이에 언마운트 · 로그아웃으로 정리가 끝났으면 연결하지 않는다 — 진행 중이던
      // 발급이 닫힌 연결을 되살리지 않게 한다. 티켓은 쓰이지 않은 채 버려지고 곧 만료된다
      if (isStopped) return;

      const opened = new EventSourceCtor(streamUrl(ticket));
      source = opened;

      opened.addEventListener('open', handleOpen);
      opened.addEventListener('error', handleError);
      // 이벤트 이름은 알림 유형과 같다 (명세 1.4). 이름 없는 onmessage에 기대지 않는다
      NOTIFICATION_TYPES.forEach((type) => opened.addEventListener(type, listenerByType[type]));
    };

    const disconnect = () => {
      if (source === null) return;
      source.removeEventListener('open', handleOpen);
      source.removeEventListener('error', handleError);
      NOTIFICATION_TYPES.forEach((type) => source?.removeEventListener(type, listenerByType[type]));
      source.close();
      source = null;
    };

    void connect();

    return () => {
      isStopped = true;
      clearTimeout(reconnectTimer);
      disconnect();
    };
  }, [queryClient]);

  return null;
}

/** 1s · 2s · 4s … 30s에서 멈춘다 */
function backoffDelay(retryCount: number): number {
  return Math.min(RECONNECT_BASE_DELAY_MS * 2 ** retryCount, RECONNECT_MAX_DELAY_MS);
}

/**
 * 무효화 연쇄 표의 SSE 세 행 (frontend/CLAUDE.md).
 *
 * - 모든 유형: `notification.list` — 도착 사실만 알고 본문은 목록 조회로 가져온다.
 * - `RISK_CHANGE`: 더해 `risk.analysis(id)` · `property` 전체 · `wishlist` 전체. 등급이 바뀐 경우에만
 *   오는 알림이라 재분석의 gradeChanged와 같다 — 마커 색 · 자치구 gradeCounts · previousGrade가 바뀐다.
 * - `REGISTRY_CHANGE`: 더해 `risk.registry(id)` · `risk.analysis(id)` · `property.detail(id)`.
 *   그 매물 밖은 낡지 않아 전체 쓸기가 아니다 — 표가 detail(id)을 따로 적은 것이 그 뜻이다.
 *
 * 본문에 propertyId가 없으면(형식이 어긋난 이벤트) 목록만 무효화하고 멈춘다 — 어느 매물인지 모르는 채
 * 전체를 쓸지 않는다. 그래도 목록은 새로 오므로 알림 자체는 확인할 수 있다.
 */
function invalidateForEvent(
  queryClient: QueryClient,
  type: NotificationType,
  propertyId: number | null,
): void {
  void queryClient.invalidateQueries({ queryKey: notificationQueries.list().queryKey });
  if (propertyId === null) return;

  if (type === 'RISK_CHANGE') {
    void queryClient.invalidateQueries({ queryKey: riskQueries.analysis(propertyId).queryKey });
    void queryClient.invalidateQueries({ queryKey: propertyQueries.all() });
    void queryClient.invalidateQueries({ queryKey: wishlistQueries.all() });
    return;
  }

  void queryClient.invalidateQueries({ queryKey: riskQueries.registry(propertyId).queryKey });
  void queryClient.invalidateQueries({ queryKey: riskQueries.analysis(propertyId).queryKey });
  void queryClient.invalidateQueries({ queryKey: propertyQueries.detail(propertyId).queryKey });
}

/** 이벤트 본문은 식별자와 유형뿐이다 (명세 1.4). 여기서 읽는 것은 무효화 대상인 propertyId 하나다 */
function readPropertyId(event: Event): number | null {
  const data: unknown = (event as MessageEvent<unknown>).data;
  if (typeof data !== 'string') return null;
  try {
    const parsed: unknown = JSON.parse(data);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const propertyId: unknown = (parsed as { propertyId?: unknown }).propertyId;
    return typeof propertyId === 'number' ? propertyId : null;
  } catch {
    // 본문을 해석하지 못해도 목록 무효화는 이미 했다 — 도착 사실이 화면에 드러난다
    return null;
  }
}
