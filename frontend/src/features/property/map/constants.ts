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
