import type { BoundingBox } from '../../../api/property';
import { BBOX_PRECISION, DISTRICT_LEVEL, SEOUL_BOUNDS } from './constants';
import type { KakaoMap, KakaoMaps } from './kakao';

/** 지도에서 읽은, 반올림하기 전의 표시 영역 좌표 */
export interface RawBoundingBox {
  minLat: number;
  maxLat: number;
  minLng: number;
  maxLng: number;
}

export interface MapPoint {
  lat: number;
  lng: number;
}

/** SDK가 준비되었는지 — index.html의 정적 <script>가 실행되면 참이다 */
export function isMapSdkReady(): boolean {
  return typeof window !== 'undefined' && typeof window.kakao !== 'undefined' && Boolean(window.kakao.maps);
}

/**
 * window.kakao를 읽는 곳은 이 함수와 overlay.ts뿐이다.
 * 준비되지 않은 상태에서 부르는 것은 호출 순서 오류이므로 던진다.
 */
export function requireMaps(): KakaoMaps {
  if (!isMapSdkReady()) {
    throw new Error('카카오맵 SDK가 아직 로드되지 않았다');
  }
  return window.kakao.maps;
}

const BBOX_FACTOR = 10 ** BBOX_PRECISION;

/** 부동소수 오차로 이미 자릿수에 맞는 값이 한 칸 밖으로 밀리는 것을 막는다 */
const scale = (value: number) => Number((value * BBOX_FACTOR).toFixed(6));

/**
 * 표시 영역 좌표를 밖으로 반올림한다 — min은 내림, max는 올림 (kakao-map 4장).
 * 안으로 반올림하면 가장자리 마커가 빠진다.
 */
export function roundOutward(bbox: RawBoundingBox): BoundingBox {
  return {
    minLat: Math.floor(scale(bbox.minLat)) / BBOX_FACTOR,
    maxLat: Math.ceil(scale(bbox.maxLat)) / BBOX_FACTOR,
    minLng: Math.floor(scale(bbox.minLng)) / BBOX_FACTOR,
    maxLng: Math.ceil(scale(bbox.maxLng)) / BBOX_FACTOR,
  };
}

/** 서울 전체가 보이도록 이동한다 (kakao-map 3장 — 되돌아가기는 setLevel이 아니라 setBounds다) */
export function fitSeoul(map: KakaoMap): void {
  const maps = requireMaps();
  map.setBounds(
    new maps.LatLngBounds(
      new maps.LatLng(SEOUL_BOUNDS.minLat, SEOUL_BOUNDS.minLng),
      new maps.LatLng(SEOUL_BOUNDS.maxLat, SEOUL_BOUNDS.maxLng),
    ),
  );
}

/**
 * 지도를 만들고 서울 전체 화면에 맞춘다.
 * 그때의 레벨을 최대 레벨로 걸어 서울 전체보다 축소되지 않게 한다 —
 * 실측 전이므로 레벨 값을 상수로 찍지 않고 화면 폭이 정한 값을 쓴다.
 */
export function createMap(container: HTMLElement): KakaoMap {
  const maps = requireMaps();
  const center = new maps.LatLng(
    (SEOUL_BOUNDS.minLat + SEOUL_BOUNDS.maxLat) / 2,
    (SEOUL_BOUNDS.minLng + SEOUL_BOUNDS.maxLng) / 2,
  );
  const map = new maps.Map(container, { center, level: DISTRICT_LEVEL });
  fitSeoul(map);
  map.setMaxLevel(map.getLevel());
  return map;
}

/** 자치구 단계 진입 — 자치구 경계를 얻을 수단이 없어 중심 좌표와 레벨로 이동한다 */
export function moveToPoint(map: KakaoMap, lat: number, lng: number, level: number = DISTRICT_LEVEL): void {
  const maps = requireMaps();
  map.setCenter(new maps.LatLng(lat, lng));
  map.setLevel(level);
}

/** 현재 표시 영역을 쿼리 키에 넣을 좌표로 읽는다 */
export function readBoundingBox(map: KakaoMap): BoundingBox {
  const bounds = map.getBounds();
  const southWest = bounds.getSouthWest();
  const northEast = bounds.getNorthEast();
  return roundOutward({
    minLat: southWest.getLat(),
    maxLat: northEast.getLat(),
    minLng: southWest.getLng(),
    maxLng: northEast.getLng(),
  });
}

/** 재조회 시점은 idle만이다 (kakao-map 4장). 해제 함수를 돌려준다 */
export function addIdleListener(map: KakaoMap, handler: () => void): () => void {
  const maps = requireMaps();
  maps.event.addListener(map, 'idle', handler);
  return () => {
    maps.event.removeListener(map, 'idle', handler);
  };
}

/** 지도 빈 곳 클릭 — 미리보기 카드를 닫는 데 쓴다. 오버레이 안 클릭은 clickable로 막혀 여기 오지 않는다 */
export function addClickListener(map: KakaoMap, handler: () => void): () => void {
  const maps = requireMaps();
  maps.event.addListener(map, 'click', handler);
  return () => {
    maps.event.removeListener(map, 'click', handler);
  };
}

/** 패널 열림 · 창 크기 변경으로 컨테이너 크기가 바뀌면 부른다 */
export function relayoutMap(map: KakaoMap): void {
  map.relayout();
}

/** 자치구 중심 좌표는 런타임에 한 번만 조회한다 — 같은 구를 두 번 부르지 않는다 */
const districtPointCache = new Map<string, Promise<MapPoint>>();

function requestDistrictPoint(district: string): Promise<MapPoint> {
  const maps = requireMaps();
  const geocoder = new maps.services.Geocoder();
  return new Promise<MapPoint>((resolve, reject) => {
    geocoder.addressSearch(`서울특별시 ${district}`, (result, status) => {
      const first = result[0];
      if (status !== maps.services.Status.OK || !first) {
        reject(new Error(`자치구 중심 좌표를 찾지 못했다: ${district} (${status})`));
        return;
      }
      resolve({ lat: Number(first.y), lng: Number(first.x) });
    });
  });
}

export function searchDistrictPoint(district: string): Promise<MapPoint> {
  const cached = districtPointCache.get(district);
  if (cached) {
    return cached;
  }
  const pending = requestDistrictPoint(district);
  districtPointCache.set(district, pending);
  // 실패는 캐시에 남기지 않는다 — 다음 선택에서 다시 조회할 수 있어야 한다
  pending.catch(() => {
    if (districtPointCache.get(district) === pending) {
      districtPointCache.delete(district);
    }
  });
  return pending;
}
