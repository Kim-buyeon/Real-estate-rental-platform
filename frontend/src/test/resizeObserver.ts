// 테스트용 가짜 ResizeObserver. jsdom에는 ResizeObserver가 없어 relayout 테스트가 컨테이너 크기
// 변화를 흉내 내려면 전역에 주입해야 한다 — kakao.ts · eventSource.ts와 같은 성격, 같은 이유
// (테스트 전략 문서 1.2). 라이브러리를 더하지 않는다.
//
// 표준 ResizeObserver 중 MapExplorer가 실제로 쓰는 부분만 흉내 낸다 — observe · disconnect와,
// 테스트가 크기 변화를 손으로 흉내 내는 수단(trigger)뿐이다. 실제 크기 측정은 하지 않는다 —
// MapExplorer의 콜백이 스스로 container.clientWidth · clientHeight를 읽으므로, 크기는 테스트가
// 대상 엘리먼트에 직접(Object.defineProperty) 둔다.

export class FakeResizeObserver implements ResizeObserver {
  /** 만들어진 인스턴스를 꺼내는 수단의 저장소. 생성될 때마다 끝에 쌓인다 */
  static instances: FakeResizeObserver[] = [];

  private readonly callback: ResizeObserverCallback;

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback;
    FakeResizeObserver.instances.push(this);
  }

  observe(): void {
    // 어떤 엘리먼트를 관찰하는지는 이 테스트가 신경 쓰지 않는다 — MapExplorer는 컨테이너 하나만 관찰한다
  }

  unobserve(): void {
    // 구현이 부르지 않는다 — 표면을 넓히지 않기 위해 아무 일도 하지 않는다
  }

  disconnect(): void {
    // 언마운트 정리를 흉내 낼 필요가 없다 — 인스턴스 목록은 install/uninstall이 관리한다
  }

  /**
   * 테스트가 크기 변화를 흉내 내는 수단. entries · observer 인자는 MapExplorer의 handleResize가
   * 읽지 않아(컨테이너 크기는 직접 DOM에서 다시 읽는다) 빈 값으로 둔다.
   */
  trigger(): void {
    this.callback([], this);
  }
}

/** 만들어진 인스턴스를 꺼내는 수단 */
export function getResizeObserverInstances(): readonly FakeResizeObserver[] {
  return FakeResizeObserver.instances;
}

/**
 * globalThis.ResizeObserver에 넣고 되돌리는 헬퍼. 반환하는 함수가 원래 상태(jsdom 기본값 —
 * ResizeObserver 없음)로 되돌린다 — 테스트 사이에 남지 않는다.
 */
export function installFakeResizeObserver(): () => void {
  const hadOwnProperty = Object.prototype.hasOwnProperty.call(globalThis, 'ResizeObserver');
  const original = globalThis.ResizeObserver;
  FakeResizeObserver.instances = [];
  globalThis.ResizeObserver = FakeResizeObserver;
  return () => {
    if (hadOwnProperty) {
      globalThis.ResizeObserver = original;
    } else {
      delete (globalThis as unknown as Record<string, unknown>).ResizeObserver;
    }
    FakeResizeObserver.instances = [];
  };
}
