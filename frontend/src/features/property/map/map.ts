import type { BoundingBox } from '../../../api/property';
import { BBOX_PRECISION, DISTRICT_LEVEL, SEOUL_BOUNDS, SEOUL_INITIAL_LEVEL } from './constants';
import type { KakaoMap, KakaoMaps } from './kakao';
import { isMapSdkReady, loadKakaoMaps } from './loader';

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

/**
 * 준비된 SDK를 꺼낸다. window.kakao 전역을 읽는 곳은 이 함수와 loader.ts 둘뿐이다 — overlay.ts도
 * 이 함수를 거친다. 준비되지 않은 상태(loadKakaoMaps가 끝나기 전)에서 부르는 것은 호출 순서 오류이므로 던진다.
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

/** 지도를 만들고 서울 전체 화면에 맞춘다. 최대 레벨은 크기가 확정된 뒤 lockSeoulView가 건다 */
export function createMap(container: HTMLElement): KakaoMap {
  const maps = requireMaps();
  const center = new maps.LatLng(
    (SEOUL_BOUNDS.minLat + SEOUL_BOUNDS.maxLat) / 2,
    (SEOUL_BOUNDS.minLng + SEOUL_BOUNDS.maxLng) / 2,
  );
  const map = new maps.Map(container, { center, level: SEOUL_INITIAL_LEVEL });
  fitSeoul(map);
  return map;
}

/**
 * 컨테이너 크기가 확정된 뒤 서울 전체에 다시 맞추고, 그때의 레벨을 최대 레벨로 건다.
 *
 * 크기가 0인 상태에서 setBounds 하면 엉뚱하게 축소된 레벨이 나오고, 그 값을 최대 레벨로 걸면
 * 전국이 보이는 화면에서 더 확대되지 않는다. 실측 전이므로 레벨 값을 상수로 찍지 않고
 * 화면 폭이 정한 값을 쓴다 (kakao-map 3장 1단계).
 */
export function lockSeoulView(map: KakaoMap): void {
  map.relayout();
  fitSeoul(map);
  map.setMaxLevel(map.getLevel());
}

/** 특정 영역으로 확대해 들어간다 — 묶음을 눌렀을 때 그 칸으로 */
export function fitBoundingBox(map: KakaoMap, bbox: BoundingBox): void {
  const maps = requireMaps();
  map.setBounds(
    new maps.LatLngBounds(new maps.LatLng(bbox.minLat, bbox.minLng), new maps.LatLng(bbox.maxLat, bbox.maxLng)),
  );
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

async function requestDistrictPoint(district: string): Promise<MapPoint> {
  // Geocoder는 services 라이브러리다 — SDK 로드가 끝난 뒤에만 있다. 로드 실패는 그대로 reject로 전해진다
  await loadKakaoMaps();
  // SDK 확인과 Geocoder 생성을 실행자 안에서 한다 — 밖에서 던지면 호출자의 catch가 받지 못한다
  return new Promise<MapPoint>((resolve, reject) => {
    const maps = requireMaps();
    const geocoder = new maps.services.Geocoder();
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
