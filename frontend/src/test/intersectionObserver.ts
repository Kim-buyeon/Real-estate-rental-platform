// 테스트용 가짜 IntersectionObserver. jsdom에는 IntersectionObserver가 없어 목록 끝 감시(다음 쪽 받기)를
// 흉내 내려면 전역에 주입해야 한다 — resizeObserver.ts와 같은 성격, 같은 이유(테스트 전략 문서 1.2).
// 라이브러리를 더하지 않는다.
//
// 표준 중 PropertyList가 쓰는 부분만 흉내 낸다 — observe · disconnect와, 테스트가 감시 요소가 보이게 된
// 것을 손으로 흉내 내는 수단(trigger)뿐이다. 실제 교차 계산은 하지 않는다.

export class FakeIntersectionObserver implements IntersectionObserver {
  /** 만들어진 인스턴스를 꺼내는 수단의 저장소. 생성될 때마다 끝에 쌓인다 */
  static instances: FakeIntersectionObserver[] = [];

  readonly root: Element | Document | null;
  readonly rootMargin: string;
  readonly scrollMargin: string = '0px';
  readonly thresholds: readonly number[] = [0];

  private readonly callback: IntersectionObserverCallback;
  private readonly targets: Element[] = [];

  /** disconnect가 불렸는가 */
  isDisconnected = false;

  constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
    this.callback = callback;
    this.root = options?.root ?? null;
    this.rootMargin = options?.rootMargin ?? '0px';
    FakeIntersectionObserver.instances.push(this);
  }

  observe(target: Element): void {
    this.targets.push(target);
  }

  unobserve(): void {
    // 구현이 부르지 않는다
  }

  disconnect(): void {
    this.isDisconnected = true;
  }

  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }

  /** 테스트가 관찰 대상이 보이게(또는 안 보이게) 된 것을 흉내 낸다. 엔트리는 구현이 읽는 isIntersecting 만 채운다 */
  trigger(isIntersecting: boolean): void {
    const entries = this.targets.map(
      (target) => ({ isIntersecting, target }) as unknown as IntersectionObserverEntry,
    );
    this.callback(entries, this);
  }
}

/** 만들어진 인스턴스를 꺼내는 수단 */
export function getIntersectionObserverInstances(): readonly FakeIntersectionObserver[] {
  return FakeIntersectionObserver.instances;
}

/**
 * globalThis.IntersectionObserver에 넣고 되돌리는 헬퍼. 반환하는 함수가 원래 상태(jsdom 기본값 —
 * IntersectionObserver 없음)로 되돌린다 — 테스트 사이에 남지 않는다.
 */
export function installFakeIntersectionObserver(): () => void {
  const hadOwnProperty = Object.prototype.hasOwnProperty.call(globalThis, 'IntersectionObserver');
  const original = globalThis.IntersectionObserver;
  FakeIntersectionObserver.instances = [];
  globalThis.IntersectionObserver = FakeIntersectionObserver;
  return () => {
    if (hadOwnProperty) {
      globalThis.IntersectionObserver = original;
    } else {
      delete (globalThis as unknown as Record<string, unknown>).IntersectionObserver;
    }
    FakeIntersectionObserver.instances = [];
  };
}
