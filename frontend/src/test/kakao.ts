// 테스트용 가짜 카카오맵 SDK. jsdom에는 window.kakao가 없어(kakao.d.ts) relayout 테스트가
// 전역에 주입한다. 저장소가 직접 쓴 가짜 클래스다 — eventSource.ts와 같은 성격, 같은 이유
// (테스트 전략 문서 1.2 「jsdom에는 EventSource가 없다 … 라이브러리를 더하지 않는다」).
//
// 표면은 frontend/src/features/property/map/kakao.d.ts가 정본이다. 그 파일이 선언한 것만
// 만든다 — 그보다 넓게 만들지 않는다.
import { vi } from 'vitest';
import type {
  KakaoAddressSearchResult,
  KakaoCustomOverlay,
  KakaoCustomOverlayOptions,
  KakaoEvent,
  KakaoGeocoder,
  KakaoLatLng,
  KakaoLatLngBounds,
  KakaoMap,
  KakaoMapOptions,
  KakaoMaps,
  KakaoServices,
  KakaoStatus,
} from '../features/property/map/kakao';

export class FakeKakaoLatLng implements KakaoLatLng {
  private readonly lat: number;
  private readonly lng: number;

  constructor(lat: number, lng: number) {
    this.lat = lat;
    this.lng = lng;
  }

  getLat(): number {
    return this.lat;
  }

  getLng(): number {
    return this.lng;
  }
}

export class FakeKakaoLatLngBounds implements KakaoLatLngBounds {
  private readonly southWest: KakaoLatLng;
  private readonly northEast: KakaoLatLng;

  constructor(southWest: KakaoLatLng, northEast: KakaoLatLng) {
    this.southWest = southWest;
    this.northEast = northEast;
  }

  getSouthWest(): KakaoLatLng {
    return this.southWest;
  }

  getNorthEast(): KakaoLatLng {
    return this.northEast;
  }
}

/**
 * 표준 KakaoMap 중 구현이 실제로 쓰는 부분 전체를 흉내 낸다. 호출을 관찰할 수 있어야 하는
 * relayout을 포함해 전부 vi.fn()으로 둔다 — relayout 테스트가 호출 여부 · 횟수를 단언한다.
 * getBounds · getLevel은 읽는 쪽(readBoundingBox · lockSeoulView)이 곧바로 쓰므로 고정값을
 * 돌려준다 — 값 자체는 이 테스트가 검증할 대상이 아니다.
 */
export class FakeKakaoMap implements KakaoMap {
  /** 만들어진 인스턴스를 꺼내는 수단의 저장소. 생성될 때마다 끝에 쌓인다 */
  static instances: FakeKakaoMap[] = [];

  readonly container: HTMLElement;
  readonly options: KakaoMapOptions;

  setBounds = vi.fn();
  setCenter = vi.fn();
  setLevel = vi.fn();
  setMaxLevel = vi.fn();
  relayout = vi.fn();
  getLevel = vi.fn(() => 8);
  getBounds = vi.fn(
    (): KakaoLatLngBounds =>
      new FakeKakaoLatLngBounds(new FakeKakaoLatLng(37.4, 126.7), new FakeKakaoLatLng(37.7, 127.2)),
  );

  constructor(container: HTMLElement, options: KakaoMapOptions) {
    this.container = container;
    this.options = options;
    FakeKakaoMap.instances.push(this);
  }
}

export class FakeKakaoCustomOverlay implements KakaoCustomOverlay {
  private readonly options: KakaoCustomOverlayOptions;

  setMap = vi.fn();
  setPosition = vi.fn();
  setZIndex = vi.fn();

  constructor(options: KakaoCustomOverlayOptions) {
    this.options = options;
  }

  getContent(): HTMLElement {
    return this.options.content;
  }
}

/** 자치구 중심 좌표 조회 — 기본은 항상 실패(ZERO_RESULT)한다. map.ts는 실패를 캐시에 남기지 않고
 * 호출자(useDistrictPoints)가 잡으므로, relayout 검증에는 좌표가 실제로 필요하지 않다. */
export class FakeKakaoGeocoder implements KakaoGeocoder {
  addressSearch = vi.fn(
    (_address: string, callback: (result: KakaoAddressSearchResult[], status: KakaoStatus) => void) => {
      callback([], 'ZERO_RESULT');
    },
  );
}

const FAKE_STATUS: Record<KakaoStatus, KakaoStatus> = { OK: 'OK', ZERO_RESULT: 'ZERO_RESULT', ERROR: 'ERROR' };

const fakeEvent: KakaoEvent = {
  addListener: vi.fn(),
  removeListener: vi.fn(),
};

const fakeServices: KakaoServices = {
  Geocoder: FakeKakaoGeocoder,
  Status: FAKE_STATUS,
};

function createFakeMaps(): KakaoMaps {
  return {
    // 이미 준비된 SDK다 — 로더는 생성자가 있으면 스크립트를 넣지 않으므로 이 경로를 타지 않지만, 표면은 맞춘다
    load: (callback) => callback(),
    LatLng: FakeKakaoLatLng,
    LatLngBounds: FakeKakaoLatLngBounds,
    Map: FakeKakaoMap,
    CustomOverlay: FakeKakaoCustomOverlay,
    event: fakeEvent,
    services: fakeServices,
  };
}

/** 만들어진 지도 인스턴스를 꺼내는 수단 */
export function getKakaoMapInstances(): readonly FakeKakaoMap[] {
  return FakeKakaoMap.instances;
}

/**
 * window.kakao에 넣고 되돌리는 헬퍼. 구현은 호출 시점에 window.kakao를 읽으므로
 * (map.ts requireMaps · loader.ts isMapSdkReady) 렌더 전에 호출해야 한다. 넣어 두면 로더가
 * 스크립트 없이 곧바로 준비로 판정한다. 반환하는 함수가 원래 상태로 되돌린다 —
 * 기본 상태(window.kakao 없음)로 돌아가야 SDK 없음 경로를 검증하는 다른 테스트에
 * 영향을 주지 않는다(MapPage.test.tsx · PropertyDetailPanel.test.tsx).
 */
export function installFakeKakaoMaps(): () => void {
  const hadOwnProperty = Object.prototype.hasOwnProperty.call(window, 'kakao');
  const original = window.kakao;
  FakeKakaoMap.instances = [];
  window.kakao = { maps: createFakeMaps() };
  return () => {
    if (hadOwnProperty) {
      window.kakao = original;
    } else {
      // jsdom 기본 상태에는 kakao 자체가 없다 — 되돌릴 때 지운다. Window 타입은 kakao를 필수로
      // 선언하므로(kakao.d.ts) delete는 unknown을 거쳐 좁힌다.
      delete (window as unknown as Record<string, unknown>).kakao;
    }
    FakeKakaoMap.instances = [];
  };
}
