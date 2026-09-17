// 테스트용 가짜 EventSource. jsdom에는 EventSource가 없어(테스트 전략 문서 1.2) 전역에 주입한다.
// 저장소가 직접 쓴 가짜 클래스다 — 라이브러리를 더하지 않는다. 필요한 것은 addEventListener ·
// removeEventListener · close · readyState · url과, 테스트가 이벤트를 밀어 넣는 수단 · 열린
// 인스턴스를 꺼내는 수단뿐이다 (frontend/CLAUDE.md 알림 수신 · 테스트 전략 문서 1.2).
//
// readyState는 표준 EventSource와 같은 값 규약을 따른다 — 0 CONNECTING · 1 OPEN · 2 CLOSED.
// app/NotificationStream.tsx는 EventSource.CLOSED 정적 속성을 읽지 않고 이 값을 상수로 박아 쓴다
// (READY_STATE_CLOSED = 2) — 이 가짜가 정적 상수(CONNECTING · OPEN · CLOSED)를 갖지 않아도 되는 이유다.

export const READY_STATE_CONNECTING = 0;
export const READY_STATE_OPEN = 1;
export const READY_STATE_CLOSED = 2;

type FakeListener = (event: Event) => void;

/**
 * 표준 EventSource 중 구현이 실제로 쓰는 부분만 흉내 낸다 — addEventListener · removeEventListener ·
 * close · readyState · url. onmessage · withCredentials 등 구현이 쓰지 않는 것은 두지 않는다.
 */
export class FakeEventSource {
  /** 열린 인스턴스를 꺼내는 수단의 저장소. connect()가 새 연결을 열 때마다 끝에 쌓인다 */
  static instances: FakeEventSource[] = [];

  readyState: number = READY_STATE_CONNECTING;
  url: string;
  private readonly listeners = new Map<string, Set<FakeListener>>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: FakeListener): void {
    const set = this.listeners.get(type) ?? new Set<FakeListener>();
    set.add(listener);
    this.listeners.set(type, set);
  }

  removeEventListener(type: string, listener: FakeListener): void {
    this.listeners.get(type)?.delete(listener);
  }

  close(): void {
    this.readyState = READY_STATE_CLOSED;
  }

  /**
   * 테스트가 이벤트를 밀어 넣는 수단. 'open'이면 readyState도 표준처럼 OPEN으로 바뀐다 — 'error'와
   * CLOSED 여부는 테스트가 readyState를 직접 두고 emit한다(구현이 자기 손으로 CLOSED로 바꾸지 않는다).
   * data가 있으면 MessageEvent로 감싼다 — 구현이 `(event as MessageEvent<unknown>).data`로 읽는다
   * (app/NotificationStream.tsx readPropertyId, 알림 API 명세 1.4).
   */
  emit(type: string, data?: unknown): void {
    if (type === 'open') this.readyState = READY_STATE_OPEN;
    const event =
      data === undefined ? new Event(type) : new MessageEvent(type, { data: typeof data === 'string' ? data : JSON.stringify(data) });
    this.listeners.get(type)?.forEach((listener) => listener(event));
  }
}

/** 열린 인스턴스를 꺼내는 수단 */
export function getEventSourceInstances(): readonly FakeEventSource[] {
  return FakeEventSource.instances;
}

/**
 * globalThis.EventSource에 넣고 되돌리는 헬퍼. 구현은 연결 시점에 globalThis에서 읽으므로
 * (frontend/CLAUDE.md 알림 수신 「모듈 최상위에서 참조를 잡아 두지 않는다」) 렌더 전에 호출해야 한다.
 * 반환하는 함수가 원래 값으로 되돌리고 인스턴스 목록을 비운다 — 테스트 사이에 남지 않는다.
 */
export function installFakeEventSource(): () => void {
  const original = globalThis.EventSource;
  FakeEventSource.instances = [];
  // jsdom에는 원래 EventSource가 없어 전역에 새로 만든다 — 표준 EventSource 인터페이스 전체를
  // 흉내 내지 않으므로 unknown을 거쳐 캐스팅한다 (frontend/CLAUDE.md 타입·열거값 「!` 단언은 이유를
  // 주석으로 남길 때만」과 같은 이유로 unknown 경유를 쓴다).
  globalThis.EventSource = FakeEventSource as unknown as typeof EventSource;
  return () => {
    globalThis.EventSource = original;
    FakeEventSource.instances = [];
  };
}
