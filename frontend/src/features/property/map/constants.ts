/** 지도 상수. 값은 이 파일 한 곳이 갖는다 — frontend/CLAUDE.md 「지도 상수」 */

/**
 * 서울 전체 단계에서 지도가 감싸는 범위.
 *
 * 실측 전 잠정값. 서울 최남단 서초구 원지동 · 최북단 도봉구 도봉동 ·
 * 최서단 강서구 오곡동 · 최동단 강동구 강일동을 감싸는 범위.
 * kakao-map 6장 실측으로 대체한다.
 */
export const SEOUL_BOUNDS = {
  minLat: 37.41,
  maxLat: 37.72,
  minLng: 126.73,
  maxLng: 127.27,
};

/**
 * 지도를 만들 때의 초기 확대 수준. 바로 setBounds(서울 경계)가 덮으므로 화면에 남지 않는다.
 * 크기가 잡힌 뒤 lockSeoulView가 다시 맞춘다.
 */
export const SEOUL_INITIAL_LEVEL = 9;

/**
 * 자치구 하나가 화면에 들어가는 확대 수준.
 *
 * 실측 전 잠정값. 자치구 경계를 얻을 수단이 없어 setBounds 대신
 * 중심 좌표 + 이 레벨로 이동한다. kakao-map 6장 실측으로 대체한다.
 */
export const DISTRICT_LEVEL = 7;

/**
 * 표시 영역 좌표를 쿼리 키에 넣을 때의 소수 자릿수.
 *
 * 밖으로 반올림한다 — min은 내림, max는 올림 (kakao-map 4장).
 * 자릿수를 늘리면 미세한 이동마다 키가 바뀌어 캐시가 무의미해진다.
 */
export const BBOX_PRECISION = 4;

/**
 * 표시 영역을 나누는 격자의 한 변 칸 수. 확대할수록 셀이 작아져 묶음이 저절로 풀린다 —
 * 지도 레벨에 의존하지 않는다(레벨 값이 실측 전 잠정이다).
 */
export const GRID_DIVISIONS = 12;

/** 이 수 이하면 묶지 않고 전부 개별로 그린다 */
export const CLUSTER_THRESHOLD = 40;

/**
 * 겹친 오버레이의 앞뒤. 가리킨 것이 맨 앞이고, 나머지는 건수 순위(0부터)를 그대로 쓴다.
 * 건수를 그대로 계층으로 쓰면 자치구 대부분이 상한을 넘겨 전부 같은 계층이 된다.
 */
export const OVERLAY_Z_FRONT = 10_000;

/**
 * SDK 로드 상한(밀리초). 스크립트를 넣은 때부터 kakao.maps.load 콜백까지 이 안에 오지 않으면 실패로 본다 —
 * 콜백이 오지 않으면 로드가 영원히 끝나지 않아 지도 영역이 안내 없이 빈다.
 *
 * 미확정 · 잠정값. 공식 문서에 로드 시간 기준이 없어, 느린 모바일 회선에서 스크립트 두 개(sdk.js와
 * load가 부르는 본체)를 받을 여유를 크게 잡았다. 운영 계측(첫 지도 표시 시간)이 생기면 그것으로 정한다.
 */
export const SDK_LOAD_TIMEOUT_MS = 15_000;
