/**
 * 지도 계층이 밖으로 내보내는 것. 바깥은 이 파일만 import 한다.
 * window.kakao를 읽는 코드는 이 폴더 밖에 없다 — frontend/CLAUDE.md 「지도」
 */

export {
  BBOX_PRECISION,
  BBOX_LAT_TILE_UNITS_BY_LEVEL,
  BBOX_LNG_TILE_UNITS_BY_LEVEL,
  DISTRICT_LEVEL,
  GRID_CELLS_PER_LAT_TILE,
  GRID_CELLS_PER_LNG_TILE,
  GRID_MAX_DIVISIONS,
  OVERLAY_Z_FRONT,
  SEOUL_BOUNDS,
  SEOUL_INITIAL_LEVEL,
  SET_BOUNDS_DEEPEST_LEVEL,
} from './constants';

export {
  addClickListener,
  addIdleListener,
  bboxTileUnits,
  canZoomInto,
  createMap,
  fitBoundingBox,
  fitSeoul,
  gridSize,
  lockSeoulView,
  moveToPoint,
  readMapArea,
  relayoutMap,
  roundOutward,
  searchDistrictPoint,
} from './map';
export type { BboxTileUnits, MapArea, MapPoint, RawBoundingBox } from './map';

export { isMapSdkReady, loadKakaoMaps } from './loader';

export { toMarkerCluster } from './markerCluster';
export type { MarkerCluster } from './markerCluster';

export { createOverlayLayer } from './overlay';
export type { OverlayItem, OverlayLayer } from './overlay';

export type { KakaoCustomOverlay, KakaoLatLng, KakaoMap } from './kakao';
