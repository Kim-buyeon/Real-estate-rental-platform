/**
 * 지도 계층이 밖으로 내보내는 것. 바깥은 이 파일만 import 한다.
 * window.kakao를 읽는 코드는 이 폴더 밖에 없다 — frontend/CLAUDE.md 「지도」
 */

export { BBOX_PRECISION, DISTRICT_LEVEL, OVERLAY_Z_FRONT, SEOUL_BOUNDS, SEOUL_INITIAL_LEVEL } from './constants';

export {
  addClickListener,
  addIdleListener,
  createMap,
  fitBoundingBox,
  fitSeoul,
  isMapSdkReady,
  lockSeoulView,
  moveToPoint,
  readBoundingBox,
  relayoutMap,
  roundOutward,
  searchDistrictPoint,
} from './map';
export type { MapPoint, RawBoundingBox } from './map';

export { CLUSTER_THRESHOLD, GRID_DIVISIONS, groupMarkers } from './cluster';
export type { GroupedMarkers, MarkerCluster } from './cluster';

export { createOverlayLayer } from './overlay';
export type { OverlayItem, OverlayLayer } from './overlay';

export type { KakaoCustomOverlay, KakaoLatLng, KakaoMap } from './kakao';
