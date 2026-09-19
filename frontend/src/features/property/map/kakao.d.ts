/**
 * 카카오맵 JavaScript SDK 중 우리가 쓰는 표면만 자체 선언한다.
 *
 * 타입 패키지를 의존성으로 추가하지 않는다 — kakao-map 3장 ⑤ · 이슈 #85 계획.
 * 여기에 없는 API를 쓰게 되면 먼저 이 파일에 선언을 추가한다.
 * SDK는 loader.ts가 지도 화면에서 동적으로 넣는다(autoload=false). 런타임 값은 window.kakao 하나다.
 */

export interface KakaoLatLng {
  getLat(): number;
  getLng(): number;
}

export interface KakaoLatLngBounds {
  getSouthWest(): KakaoLatLng;
  getNorthEast(): KakaoLatLng;
}

export interface KakaoMapOptions {
  center: KakaoLatLng;
  level: number;
}

export interface KakaoMap {
  setBounds(bounds: KakaoLatLngBounds): void;
  setCenter(position: KakaoLatLng): void;
  setLevel(level: number): void;
  getLevel(): number;
  setMaxLevel(maxLevel: number): void;
  getBounds(): KakaoLatLngBounds;
  relayout(): void;
}

/** content는 엘리먼트만 받는다 — HTML 문자열을 막기 위해 좁혀 선언한다 (kakao-map 5장) */
export interface KakaoCustomOverlayOptions {
  position: KakaoLatLng;
  content: HTMLElement;
  clickable?: boolean;
  xAnchor?: number;
  yAnchor?: number;
  zIndex?: number;
}

export interface KakaoCustomOverlay {
  setMap(map: KakaoMap | null): void;
  setPosition(position: KakaoLatLng): void;
  setZIndex(zIndex: number): void;
  getContent(): HTMLElement;
}

export type KakaoEventTarget = KakaoMap;

export interface KakaoEvent {
  addListener(target: KakaoEventTarget, type: string, handler: () => void): void;
  removeListener(target: KakaoEventTarget, type: string, handler: () => void): void;
}

export type KakaoStatus = 'OK' | 'ZERO_RESULT' | 'ERROR';

/** addressSearch 결과 중 우리가 읽는 것은 좌표 둘뿐이다. x = 경도, y = 위도 (문자열) */
export interface KakaoAddressSearchResult {
  x: string;
  y: string;
}

export interface KakaoGeocoder {
  addressSearch(
    address: string,
    callback: (result: KakaoAddressSearchResult[], status: KakaoStatus) => void,
  ): void;
}

export interface KakaoServices {
  Geocoder: new () => KakaoGeocoder;
  Status: Record<KakaoStatus, KakaoStatus>;
}

export interface KakaoMaps {
  /** autoload=false일 때 SDK 본체를 불러온다. 콜백 안에서부터 아래 생성자 · services가 있다 (kakao-map 2장) */
  load(callback: () => void): void;
  LatLng: new (lat: number, lng: number) => KakaoLatLng;
  LatLngBounds: new (sw: KakaoLatLng, ne: KakaoLatLng) => KakaoLatLngBounds;
  Map: new (container: HTMLElement, options: KakaoMapOptions) => KakaoMap;
  CustomOverlay: new (options: KakaoCustomOverlayOptions) => KakaoCustomOverlay;
  event: KakaoEvent;
  services: KakaoServices;
}

declare global {
  interface Window {
    /**
     * loader.ts가 스크립트를 넣기 전에는 없고, 스크립트 실행 직후에는 load만 있다.
     * 생성자를 쓰기 전에는 loadKakaoMaps()를 기다리거나 isMapSdkReady()로 확인한다
     */
    kakao: { maps: KakaoMaps };
  }
}
