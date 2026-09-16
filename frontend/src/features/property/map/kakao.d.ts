/**
 * 카카오맵 JavaScript SDK 중 우리가 쓰는 표면만 자체 선언한다.
 *
 * 타입 패키지를 의존성으로 추가하지 않는다 — kakao-map 3장 ⑤ · 이슈 #85 계획.
 * 여기에 없는 API를 쓰게 되면 먼저 이 파일에 선언을 추가한다.
 * SDK는 index.html의 정적 <script>로 로드되므로 런타임 값은 window.kakao 하나다.
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
  LatLng: new (lat: number, lng: number) => KakaoLatLng;
  LatLngBounds: new (sw: KakaoLatLng, ne: KakaoLatLng) => KakaoLatLngBounds;
  Map: new (container: HTMLElement, options: KakaoMapOptions) => KakaoMap;
  CustomOverlay: new (options: KakaoCustomOverlayOptions) => KakaoCustomOverlay;
  event: KakaoEvent;
  services: KakaoServices;
}

declare global {
  interface Window {
    /** index.html의 <script>가 로드되기 전에는 없다. isMapSdkReady()로 확인한 뒤 읽는다 */
    kakao: { maps: KakaoMaps };
  }
}
