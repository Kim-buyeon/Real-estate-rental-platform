// 요청 조합 공용 모듈 — 트래픽 정의서 3.1(요청 조합) · 3.2(지도 단계) · 3.3(지역) · 3.4(공통 파라미터) · 8장 끝 문단.
// 조합을 바꿀 때는 이 파일 한 곳만 고친다. 프로파일 스크립트는 인가량과 지속 시간만 갖는다.
//
// 엔드포인트의 경로 · 파라미터 · 응답 모양은 백엔드 컨트롤러와 대조했다(2026-09-26 기준 main):
//   PropertyController · WishlistController · RiskController · LoanController · NotificationController · AuthController
// 모든 응답은 공통 봉투 { success, data | error } 다(ApiResponse).

import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Rate, Counter } from 'k6/metrics';
import { getSession, authHeaders, reissue } from './tokens.js';

// ── 실행 환경 ────────────────────────────────────────────────────────────
export const BASE_URL = (__ENV.BASE_URL || 'http://10.20.0.10').replace(/\/+$/, '');

// 요청 하나의 클라이언트 쪽 상한(초). 스스로 정한 값 — 앞단 Nginx 의 /api/ proxy_read_timeout 은 30초지만,
// 포화 판정 기준(p95 500ms)의 20배면 이미 포화 구간이고, 길게 두면 arrival-rate 가 VU 를 그만큼 더 잡아
// 부하 생성기가 먼저 무너진다. 이 시간을 넘긴 요청은 status 0 으로 실패에 들어간다.
export const REQ_TIMEOUT_SEC = Number(__ENV.REQ_TIMEOUT_SEC || 10);

// think time 3~10초 무작위 — 트래픽 정의서 3.4. THINK=0 이면 끈다.
// arrival-rate 에서는 반복 시작 간격을 k6 가 정하므로 think time 이 RPS 를 바꾸지 않는다. 대신 한 VU 가 한 번에
// 붙잡는 시간이 늘어 동시 VU(= 동시 세션 · 앞단으로 열린 연결 수)가 실제 사용자 수에 가까워진다. 끄면 적은 VU 가
// keep-alive 연결 몇 개로 요청을 몰아 보내 커넥션 분포가 실제와 달라진다.
export const THINK_MIN = __ENV.THINK === '0' ? 0 : Number(__ENV.THINK_MIN || 3);
export const THINK_MAX = __ENV.THINK === '0' ? 0 : Number(__ENV.THINK_MAX || 10);

// 각 단계 첫 60초는 워밍업으로 표시해 집계에서 뺀다 — 트래픽 정의서 3.4.
export const WARMUP_SEC = 60;

/**
 * arrival-rate 의 VU 수. 반복 하나 = 요청 하나 + think time 이므로 필요한 VU ≈ RPS × (응답 시간 + think time).
 * maxVUs 는 포화 구간 기준(8장) — 응답이 타임아웃 상한까지 늘어난 경우를 잡는다.
 */
export function vuBudget(peakRps) {
  const pre = Math.ceil(peakRps * ((THINK_MIN + THINK_MAX) / 2 + 1));
  const max = Math.ceil(peakRps * (THINK_MAX + REQ_TIMEOUT_SEC + 1));
  return {
    preAllocatedVUs: Number(__ENV.PRE_VUS || Math.max(pre, 5)),
    maxVUs: Number(__ENV.MAX_VUS || Math.max(max, 20)),
  };
}

// ── 요청 조합 — 트래픽 정의서 3.1 ────────────────────────────────────────
export const WEIGHTS = {
  map: 30,             // GET /api/properties (좌표 조건) — PROP-02. 아래 MAP_STAGES 로 다시 나눈다
  district_counts: 22, // GET /api/properties/district-counts — PROP-08
  prop_detail: 13,     // GET /api/properties/{id} — PROP-03
  risk: 10,            // GET /api/properties/{id}/risk — RISK-01
  prop_list: 7,        // GET /api/properties (좌표 없음) — PROP-01
  wishlist_get: 6,     // GET /api/me/wishlist — PROP-05
  noti_list: 4,        // GET /api/notifications — NOTI-05
  wishlist_write: 3,   // POST · DELETE /api/me/wishlist — PROP-05
  reissue: 2,          // POST /api/auth/reissue — USER-02
  loan_limit: 2,       // GET /api/loans/limit — LOAN-01
  reanalyze: 1,        // POST /api/properties/{id}/risk/reanalyze — RISK-08
};

// 지도 탐색 단계 — 트래픽 정의서 3.2. 코드와 맞춘 해석(보고서 「문서와 코드가 다른 곳」 참조):
//   map_s1 (30%) 「시 전체 · 자치구 단위 집계」 — 서버에 별도 조회가 없다. 서울 전체 단계의 호출은 명세(매물 1.2)와
//                 화면(api/property.ts) 모두 district-counts 이므로 그것을 부른다. 태그는 map_s1 로 따로 붙인다.
//   map_s2 (40%) 「자치구 · 동 단위 클러스터」 — 서버는 묶지 않는다(화면 cluster.ts 가 격자로 묶는다). 서버 비용은
//                 district + 자치구 확대 수준의 표시 영역 좌표 조회(바운딩 박스)다. 화면이 실제로 부르는 형태.
//   map_s3 (30%) 「동 · 개별 마커, 바운딩 박스 + 거리 계산」 — 서버에서 거리 계산을 하는 것은 반경 조건
//                 (lat · lng · radiusKm) 뿐이다(PropertyQueryService.searchMarkersInRadius). 화면은 지금 반경
//                 조건을 쓰지 않지만 문서가 이 단계의 비용을 「거리 계산」으로 정했으므로 반경 조회로 둔다.
export const MAP_STAGES = { map_s1: 30, map_s2: 40, map_s3: 30 };

// 지역 분포 — 트래픽 정의서 3.3. 상위 3개 자치구 60%, 나머지 22개 40%.
// 「상위 3개」가 어느 구인지는 문서에 없다. setup 이 district-counts 의 매물 수 상위 3개로 정한다.
// TOP_DISTRICTS=강서구,마포구,송파구 처럼 주면 그것을 쓴다.
export const TOP_COUNT = 3;
export const TOP_SHARE = 0.6;

// 필터 조합. 스스로 정한 값 — 문서에 없다. 자치구 집계는 필터 조합별로 캐시되므로(명세 매물 1.5,
// DistrictCountCacheStore) 필터가 하나뿐이면 캐시 적중률이 100% 에 붙는다(7장 「데이터 편중」). VU(= 세션)마다
// 처음에 하나를 골라 끝까지 유지한다 — 명세 1.1 「단계를 오갈 때 조건이 그대로 유지」.
export const FILTER_PRESETS = [
  { weight: 60, params: {} },
  { weight: 25, params: { contractType: 'DEPOSIT_ONLY' } },
  { weight: 15, params: { contractType: 'DEPOSIT_ONLY', depositMax: 300000000 } },
];

// 2단계 표시 영역의 반폭(도). 스스로 정한 값 — 화면은 자치구 중심 + 카카오맵 레벨 7(DISTRICT_LEVEL, 실측 전
// 잠정값)로 이동한 뒤 지도 경계를 읽는다. 레벨 7 의 실제 폭은 화면 크기에 따라 다르므로, 데스크톱 지도 패널
// 기준 약 6.7km × 7km 로 잡았다. kakao-map 스킬 6장 실측이 생기면 그 값으로 바꾼다.
export const S2_HALF_LAT = 0.03;
export const S2_HALF_LNG = 0.04;
// 3단계 반경(km). 스스로 정한 값 — 동 하나를 덮는 크기.
export const S3_RADIUS_KM = 1.0;
// 화면이 표시 영역 좌표를 쿼리에 넣을 때의 소수 자릿수(frontend map/constants.ts BBOX_PRECISION). 밖으로 반올림한다.
const BBOX_PRECISION = 4;

// 서울 전체 범위 — frontend/src/features/property/map/constants.ts 의 SEOUL_BOUNDS 와 같은 값(그쪽도 잠정값).
// setup 이 자치구별 매물 좌표를 한 번에 받을 때만 쓴다.
export const SEOUL_BOUNDS = { minLat: 37.41, maxLat: 37.72, minLng: 126.73, maxLng: 127.27 };

// 자치구 대략 중심 — setup 이 실제 매물 좌표로 경계를 못 구했을 때(그 구의 매물이 0건 등)만 쓴다.
// 코드 · 시드에는 자치구 좌표가 없다(화면은 카카오 지오코더로 그때그때 찾는다 — MapExplorer searchDistrictPoint).
// 출처: 각 구청 소재지의 대략 좌표(공개 지도 기준, 소수 넷째 자리 반올림). 부하 분산용이라 수백 m 오차는 무방하며
// 정밀 검증하지 않았다. 이름과 순서는 백엔드 SeoulDistrict enum(법정동 코드 순)과 같다.
export const FALLBACK_CENTERS = {
  '종로구': [37.5735, 126.9790], '중구': [37.5641, 126.9979], '용산구': [37.5326, 126.9905],
  '성동구': [37.5634, 127.0369], '광진구': [37.5385, 127.0823], '동대문구': [37.5744, 127.0396],
  '중랑구': [37.6066, 127.0927], '성북구': [37.5894, 127.0167], '강북구': [37.6396, 127.0257],
  '도봉구': [37.6688, 127.0471], '노원구': [37.6542, 127.0568], '은평구': [37.6027, 126.9291],
  '서대문구': [37.5791, 126.9368], '마포구': [37.5663, 126.9019], '양천구': [37.5170, 126.8664],
  '강서구': [37.5509, 126.8495], '구로구': [37.4954, 126.8874], '금천구': [37.4569, 126.8955],
  '영등포구': [37.5264, 126.8962], '동작구': [37.5124, 126.9393], '관악구': [37.4784, 126.9516],
  '서초구': [37.4837, 127.0324], '강남구': [37.5172, 127.0473], '송파구': [37.5145, 127.1066],
  '강동구': [37.5301, 127.1238],
};

// 요약 · 임계에 쓰는 엔드포인트 태그(ep). 요청마다 ep 태그와 name(경로 틀) 태그를 붙인다.
export const EP_TAGS = [
  'map_s1', 'map_s2', 'map_s3', 'district_counts', 'prop_detail', 'risk', 'prop_list',
  'wishlist_get', 'noti_list', 'wishlist_post', 'wishlist_delete', 'reissue', 'loan_limit', 'reanalyze',
];

// ── 지표 ────────────────────────────────────────────────────────────────
// 5xx 와 응답 없음(status 0 — 타임아웃 · 연결 실패). 포화 판정 「5xx 비율 1% 초과」(시험 계획서 2.3).
export const lt5xx = new Rate('lt_5xx');
// 응답 검증 통과율. 200 이어도 본문이 비었으면 실패로 센다 — 트래픽 정의서 3.4 「응답 검증」.
export const ltValid = new Rate('lt_valid');
// 배열이 비었는가. 알림 목록처럼 비어도 정상인 것은 lt_valid 대신 여기만 올린다.
export const ltEmpty = new Rate('lt_empty');
// 의도한 거절(409 WISHLIST_DUPLICATED · 429 RISK_REANALYZE_TOO_SOON · 422 LOAN_PROPERTY_NOT_ELIGIBLE).
export const ltRejected = new Counter('lt_expected_reject');
// 소유자가 아닌 VU 의 액세스 토큰 만료 — tokens.js 머리 주석 참조. 서버 문제가 아니라 풀 크기 문제다.
export const ltAuthExpiredShared = new Counter('lt_auth_expired_shared');

const OK_200 = http.expectedStatuses(200);
const OK_WISH_POST = http.expectedStatuses(201, 409);
const OK_WISH_DELETE = http.expectedStatuses(204);
const OK_LOAN = http.expectedStatuses(200, 422);
const OK_REANALYZE = http.expectedStatuses(200, 429);

// ── setup: 자치구 좌표 경계 · 시드 매물 ─────────────────────────────────────

function qs(obj) {
  const parts = [];
  Object.keys(obj).forEach(function (k) {
    const v = obj[k];
    if (v === undefined || v === null) return;
    parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v)));
  });
  return parts.length ? '?' + parts.join('&') : '';
}

function body(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

function quantile(sorted, q) {
  if (sorted.length === 0) return null;
  const i = Math.min(sorted.length - 1, Math.max(0, Math.floor(q * (sorted.length - 1))));
  return sorted[i];
}

function sampleEvenly(arr, n) {
  if (arr.length <= n) return arr.slice();
  const out = [];
  const step = arr.length / n;
  for (let i = 0; i < n; i++) out.push(arr[Math.floor(i * step)]);
  return out;
}

/**
 * setup() 에서 한 번 부른다. 측정 대상이 아니다(phase 태그가 없어 임계 · 요약 집계에 들지 않는다).
 *   1. district-counts 로 자치구별 매물 수 → 상위 3개 자치구
 *   2. 자치구마다 서울 전체 범위의 마커 조회 → 실제 매물 좌표의 5~95 분위로 자치구 경계(바운딩 박스)와 중심,
 *      상세 · 위험도 첫 호출에 쓸 시드 매물 ID, 대출 한도에 쓸 SAFE 매물 ID
 * 반환값은 setup 데이터로 모든 VU 에 복사되므로 작게 둔다(구당 ID 40 + SAFE 20).
 */
export function prepareGeo() {
  const setupTags = { ep: 'setup', name: 'setup' };
  const dc = http.get(BASE_URL + '/api/properties/district-counts', { tags: setupTags, timeout: '60s' });
  const dcBody = body(dc);
  if (dc.status !== 200 || !dcBody || !dcBody.data || !Array.isArray(dcBody.data.districts)) {
    throw new Error(`setup: district-counts 실패 status=${dc.status} — BASE_URL(${BASE_URL}) 과 앱 상태를 확인한다`);
  }
  const counts = {};
  dcBody.data.districts.forEach(function (d) { counts[d.name] = d.count; });

  let top;
  if (__ENV.TOP_DISTRICTS) {
    top = __ENV.TOP_DISTRICTS.split(',').map(function (s) { return s.trim(); }).filter(Boolean);
  } else {
    top = Object.keys(counts).sort(function (a, b) { return counts[b] - counts[a]; }).slice(0, TOP_COUNT);
  }

  const districts = [];
  Object.keys(FALLBACK_CENTERS).forEach(function (name) {
    const url = BASE_URL + '/api/properties' + qs(Object.assign({ district: name }, SEOUL_BOUNDS));
    const res = http.get(url, { tags: setupTags, timeout: '60s' });
    const b = body(res);
    const items = res.status === 200 && b && b.data && Array.isArray(b.data.items) ? b.data.items : [];
    const withCoord = items.filter(function (m) { return m.latitude !== null && m.longitude !== null; });
    let entry;
    if (withCoord.length >= 5) {
      const lats = withCoord.map(function (m) { return Number(m.latitude); }).sort(function (x, y) { return x - y; });
      const lngs = withCoord.map(function (m) { return Number(m.longitude); }).sort(function (x, y) { return x - y; });
      entry = {
        name: name,
        count: counts[name] || 0,
        source: 'data',
        center: [quantile(lats, 0.5), quantile(lngs, 0.5)],
        bbox: [quantile(lats, 0.05), quantile(lats, 0.95), quantile(lngs, 0.05), quantile(lngs, 0.95)],
      };
    } else {
      const c = FALLBACK_CENTERS[name];
      entry = {
        name: name,
        count: counts[name] || 0,
        source: 'fallback',
        center: c,
        bbox: [c[0] - S2_HALF_LAT, c[0] + S2_HALF_LAT, c[1] - S2_HALF_LNG, c[1] + S2_HALF_LNG],
      };
    }
    entry.ids = sampleEvenly(items.map(function (m) { return m.propertyId; }), 40);
    entry.safe = sampleEvenly(
      items.filter(function (m) { return m.riskGrade === 'SAFE'; }).map(function (m) { return m.propertyId; }), 20);
    districts.push(entry);
  });

  const fallbacks = districts.filter(function (d) { return d.source === 'fallback'; }).map(function (d) { return d.name; });
  if (fallbacks.length) console.warn('[mix] 실제 좌표를 못 구해 대략 중심을 쓴 자치구: ' + fallbacks.join(', '));
  const unknownTop = top.filter(function (t) { return !FALLBACK_CENTERS[t]; });
  if (unknownTop.length) throw new Error('TOP_DISTRICTS 에 서울 자치구가 아닌 이름: ' + unknownTop.join(', '));

  return { top: top, districts: districts, fallbackDistricts: fallbacks };
}

// ── VU 상태 ───────────────────────────────────────────────────────────────
// 모듈 전역은 VU 마다 따로다. 한 VU = 한 사용자 세션으로 본다.
const vu = {
  filter: null,   // 세션 동안 유지하는 필터
  seen: [],       // 이 VU 가 지도 조회에서 실제로 받은 매물 { id, lat, lng, district, safe }
  wishAdded: [],  // 이 VU 가 등록한 관심 매물 — 이것만 해제한다(make-tokens 가 넣어 둔 것은 건드리지 않는다)
};
const SEEN_MAX = 200;

function pickWeighted(map) {
  let total = 0;
  Object.keys(map).forEach(function (k) { total += map[k]; });
  let r = Math.random() * total;
  const keys = Object.keys(map);
  for (let i = 0; i < keys.length; i++) {
    r -= map[keys[i]];
    if (r < 0) return keys[i];
  }
  return keys[keys.length - 1];
}

function pickFilter() {
  const w = {};
  FILTER_PRESETS.forEach(function (p, i) { w[i] = p.weight; });
  return FILTER_PRESETS[Number(pickWeighted(w))].params;
}

function randomOf(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

/** 지역 분포 — 상위 3개 60%, 나머지 40%. */
function pickDistrict(geo) {
  const topSet = {};
  geo.top.forEach(function (t) { topSet[t] = true; });
  const pool = Math.random() < TOP_SHARE
    ? geo.districts.filter(function (d) { return topSet[d.name]; })
    : geo.districts.filter(function (d) { return !topSet[d.name]; });
  return randomOf(pool.length ? pool : geo.districts);
}

function remember(items) {
  // 받은 마커 중 최대 20건을 기억한다. 오래된 것부터 밀어낸다.
  const picks = items.length <= 20 ? items : sampleEvenly(items, 20);
  picks.forEach(function (m) {
    vu.seen.push({
      id: m.propertyId,
      lat: Number(m.latitude),
      lng: Number(m.longitude),
      district: m.district,
      safe: m.riskGrade === 'SAFE',
    });
  });
  if (vu.seen.length > SEEN_MAX) vu.seen.splice(0, vu.seen.length - SEEN_MAX);
}

/**
 * 상세 · 위험도 · 관심 매물에 쓸 ID. 여정 규칙(3.1) — 이 VU 가 지도 조회에서 받은 ID 를 우선 쓴다.
 * 아직 지도 조회를 안 한 첫 반복만 setup 이 실제 마커 조회로 받아 둔 시드 ID 를 쓴다(존재하는 ID 다).
 */
function pickPropertyId(geo, safeOnly) {
  const seen = safeOnly ? vu.seen.filter(function (s) { return s.safe; }) : vu.seen;
  if (seen.length) return randomOf(seen).id;
  const d = pickDistrict(geo);
  const ids = safeOnly ? d.safe : d.ids;
  if (ids.length) return randomOf(ids);
  // 그 구에 없으면 아무 구에서
  const any = geo.districts.filter(function (x) { return (safeOnly ? x.safe : x.ids).length; });
  return any.length ? randomOf(safeOnly ? randomOf(any).safe : randomOf(any).ids) : null;
}

function roundOutward(minLat, maxLat, minLng, maxLng) {
  const f = Math.pow(10, BBOX_PRECISION);
  return {
    minLat: Math.floor(minLat * f) / f,
    maxLat: Math.ceil(maxLat * f) / f,
    minLng: Math.floor(minLng * f) / f,
    maxLng: Math.ceil(maxLng * f) / f,
  };
}

// ── 요청 한 건 ─────────────────────────────────────────────────────────────

function errorCode(res) {
  const b = body(res);
  return b && b.error ? b.error.code : null;
}

/**
 * 요청을 보내고, 소유자 VU 의 액세스 토큰이 만료됐으면(401 AUTH_TOKEN_EXPIRED) 재발급 뒤 한 번 다시 보낸다.
 * 화면(api/client.ts 인터셉터)과 같은 동작이다. 만료 재발급은 ep=reissue_on_expiry 로 조합(reissue)과 구분한다.
 */
function send(s, ep, name, method, url, payload, auth, expected) {
  const params = {
    tags: { ep: ep, name: name },
    timeout: REQ_TIMEOUT_SEC + 's',
    headers: {},
    responseCallback: expected,
  };
  if (payload !== null) params.headers['Content-Type'] = 'application/json';
  if (auth) Object.assign(params.headers, authHeaders(s));
  const bodyText = payload === null ? null : JSON.stringify(payload);

  let res = http.request(method, url, bodyText, params);
  if (auth && res.status === 401 && errorCode(res) === 'AUTH_TOKEN_EXPIRED') {
    if (s.owner && !s.broken) {
      const r = reissue(s, BASE_URL, {
        tags: { ep: 'reissue_on_expiry', name: 'POST /api/auth/reissue' },
        timeout: REQ_TIMEOUT_SEC + 's',
        responseCallback: OK_200,
      });
      lt5xx.add(r.status >= 500 || r.status === 0, { ep: 'reissue_on_expiry' });
      if (r.status === 200) {
        params.headers = Object.assign({}, params.headers, authHeaders(s));
        res = http.request(method, url, bodyText, params);
      }
    } else {
      ltAuthExpiredShared.add(1, { ep: ep });
    }
  }
  lt5xx.add(res.status >= 500 || res.status === 0, { ep: ep });
  return res;
}

function validate(ep, res, fn) {
  let ok = false;
  try {
    ok = !!fn(body(res));
  } catch (e) {
    ok = false;
  }
  const sets = {};
  sets[ep + ' 응답 검증'] = function () { return ok; };
  check(res, sets, { ep: ep });
  ltValid.add(ok, { ep: ep });
  return ok;
}

function nonEmptyItems(ep, res, extra) {
  return validate(ep, res, function (b) {
    const ok = b.success === true && Array.isArray(b.data.items);
    if (ok) ltEmpty.add(b.data.items.length === 0, { ep: ep });
    return ok && b.data.items.length > 0 && (!extra || extra(b.data));
  });
}

// ── 엔드포인트별 동작 ──────────────────────────────────────────────────────

function districtCounts(s, ep) {
  // GET /api/properties/district-counts — 응답 data { districts[{name,count,gradeCounts}], totalCount, aggregatedAt }
  const res = send(s, ep, 'GET /api/properties/district-counts', 'GET',
    BASE_URL + '/api/properties/district-counts' + qs(vu.filter), null, true, OK_200);
  validate(ep, res, function (b) {
    const ok = b.success === true && Array.isArray(b.data.districts);
    if (ok) ltEmpty.add(b.data.districts.length === 0, { ep: ep });
    return ok && b.data.districts.length > 0;
  });
}

function mapStage2(s, geo) {
  // GET /api/properties/map-clusters?district&minLat&maxLat&minLng&maxLng — 응답 data { total, clustered, clusters[], markers[] }
  // (API 명세 매물 1.12, #263). 화면의 자치구 단계와 같은 호출이다 — 전에는 마커 전량(GET /api/properties + 좌표)을 받아 화면이 묶었다
  const d = pickDistrict(geo);
  // 자치구 경계 안의 임의 지점을 중심으로 레벨 7 크기의 표시 영역 — 사용자가 구 안에서 지도를 옮기는 것
  const lat = d.bbox[0] + Math.random() * (d.bbox[1] - d.bbox[0]);
  const lng = d.bbox[2] + Math.random() * (d.bbox[3] - d.bbox[2]);
  const box = roundOutward(lat - S2_HALF_LAT, lat + S2_HALF_LAT, lng - S2_HALF_LNG, lng + S2_HALF_LNG);
  const res = send(s, 'map_s2', 'GET /api/properties/map-clusters', 'GET',
    BASE_URL + '/api/properties/map-clusters' + qs(Object.assign({ district: d.name }, vu.filter, box)), null, true, OK_200);
  const ok = validate('map_s2', res, function (b) {
    const data = b.data;
    const shaped = b.success === true && Array.isArray(data.clusters) && Array.isArray(data.markers);
    if (shaped) ltEmpty.add(data.total === 0, { ep: 'map_s2' });
    // 묶음 건수 + 개별 마커 수 = total 이어야 한다 — 칸을 빠뜨리거나 두 번 세지 않았는지
    const counted = shaped && data.clusters.reduce(function (a, c) { return a + c.count; }, 0) + data.markers.length;
    return shaped && data.total > 0 && counted === data.total;
  });
  // 3단계 · 상세는 매물 id 가 필요하다 — 개별 마커(한 건 칸 · 40건 이하 영역)만 기억한다
  if (ok) remember(body(res).data.markers);
}

function mapStage3(s, geo) {
  // GET /api/properties?district&lat&lng&radiusKm — 바운딩 박스 1차 조회 + Haversine 거리 계산 · 거리순
  // 중심은 이 VU 가 이미 받은 마커 좌표다 — 같은 필터로 받은 매물이므로 결과가 최소 1건이다.
  if (vu.seen.length === 0) {
    mapStage2(s, geo); // 아직 본 마커가 없으면 구 단계부터(여정 순서)
    return;
  }
  const m = randomOf(vu.seen);
  const res = send(s, 'map_s3', 'GET /api/properties (radius)', 'GET',
    BASE_URL + '/api/properties' + qs(Object.assign({ district: m.district }, vu.filter,
      { lat: m.lat, lng: m.lng, radiusKm: S3_RADIUS_KM })), null, true, OK_200);
  const ok = nonEmptyItems('map_s3', res, function (data) { return data.count === data.items.length; });
  if (ok) remember(body(res).data.items);
}

function propList(s, geo) {
  // GET /api/properties?district&size — 좌표 없음 → CursorPage { items, nextCursor?, hasNext }. 기본 정렬 등록일 내림차순
  const d = pickDistrict(geo);
  const res = send(s, 'prop_list', 'GET /api/properties (list)', 'GET',
    BASE_URL + '/api/properties' + qs(Object.assign({ district: d.name }, vu.filter, { size: 20 })), null, true, OK_200);
  nonEmptyItems('prop_list', res, function (data) { return typeof data.hasNext === 'boolean'; });
}

function propDetail(s, geo) {
  // GET /api/properties/{id} — 인증 선택. 토큰이 있으면 wishlisted 조인이 붙는다(PropertyDetailCondition)
  const id = pickPropertyId(geo, false);
  const res = send(s, 'prop_detail', 'GET /api/properties/{id}', 'GET',
    BASE_URL + '/api/properties/' + id, null, true, OK_200);
  validate('prop_detail', res, function (b) { return b.success === true && b.data.propertyId === id; });
}

function risk(s, geo) {
  // GET /api/properties/{id}/risk — 조회 시점에 분석을 수행한다(RiskController 주석). 결과가 바뀌면 이력을 쓴다.
  // 응답 providers 는 HUG · HF · SGI 3건.
  const id = pickPropertyId(geo, false);
  const res = send(s, 'risk', 'GET /api/properties/{id}/risk', 'GET',
    BASE_URL + '/api/properties/' + id + '/risk', null, true, OK_200);
  validate('risk', res, function (b) {
    return b.success === true && ['SAFE', 'CAUTION', 'DANGER'].indexOf(b.data.riskGrade) >= 0 &&
      Array.isArray(b.data.providers) && b.data.providers.length === 3;
  });
}

function wishlistGet(s) {
  // GET /api/me/wishlist — CursorPage { items[{propertyId,district,deposit,riskGrade,previousGrade,addedAt}] }
  // make-tokens.js 가 계정마다 관심 매물을 넣어 두므로 비면 실패다.
  const res = send(s, 'wishlist_get', 'GET /api/me/wishlist', 'GET', BASE_URL + '/api/me/wishlist', null, true, OK_200);
  nonEmptyItems('wishlist_get', res, null);
}

function notiList(s) {
  // GET /api/notifications — { items, nextCursor?, hasNext, unreadCount }
  // 알림은 관심 매물의 등급 · 등기 변동이 있어야 생긴다. 비어 있는 것이 정상일 수 있으므로 비었는지는 lt_empty 로만 센다.
  const res = send(s, 'noti_list', 'GET /api/notifications', 'GET', BASE_URL + '/api/notifications', null, true, OK_200);
  validate('noti_list', res, function (b) {
    const ok = b.success === true && Array.isArray(b.data.items) && typeof b.data.unreadCount === 'number';
    if (ok) ltEmpty.add(b.data.items.length === 0, { ep: 'noti_list' });
    return ok;
  });
}

function wishlistWrite(s, geo) {
  // 등록 POST /api/me/wishlist { propertyId } → 201(이미 있으면 409 WISHLIST_DUPLICATED).
  // 해제 DELETE /api/me/wishlist/{id} → 204(멱등). VU 당 등록분을 3건 이하로 유지해 계정 목록이 계속 불지 않게 한다.
  const del = vu.wishAdded.length >= 3 || (vu.wishAdded.length > 0 && Math.random() < 0.5);
  if (del) {
    const id = vu.wishAdded.shift();
    const res = send(s, 'wishlist_delete', 'DELETE /api/me/wishlist/{id}', 'DELETE',
      BASE_URL + '/api/me/wishlist/' + id, null, true, OK_WISH_DELETE);
    validate('wishlist_delete', res, function () { return res.status === 204; });
    return;
  }
  const id = pickPropertyId(geo, false);
  const res = send(s, 'wishlist_post', 'POST /api/me/wishlist', 'POST',
    BASE_URL + '/api/me/wishlist', { propertyId: id }, true, OK_WISH_POST);
  if (res.status === 201) vu.wishAdded.push(id);
  if (res.status === 409) ltRejected.add(1, { ep: 'wishlist_post' });
  validate('wishlist_post', res, function (b) {
    return (res.status === 201 && b.success === true) ||
      (res.status === 409 && b.error.code === 'WISHLIST_DUPLICATED');
  });
}

function doReissue(s) {
  // POST /api/auth/reissue — Redis 를 치는 상시 요청(3.1). 토큰 회전 포함.
  const res = reissue(s, BASE_URL, {
    tags: { ep: 'reissue', name: 'POST /api/auth/reissue' },
    timeout: REQ_TIMEOUT_SEC + 's',
    responseCallback: OK_200,
  });
  lt5xx.add(res.status >= 500 || res.status === 0, { ep: 'reissue' });
  validate('reissue', res, function (b) {
    return b.success === true && typeof b.data.accessToken === 'string' && typeof b.data.refreshToken === 'string';
  });
}

function loanLimit(s, geo) {
  // GET /api/loans/limit?propertyId — 인증 필수. 보증보험 가입 불가 매물이면 422 LOAN_PROPERTY_NOT_ELIGIBLE 이므로
  // 마커에서 SAFE 로 본 매물(가입 가능)만 쓴다. 기본 자격 정보(무주택)면 PROFILE_INCOMPLETE 는 나지 않는다.
  const id = pickPropertyId(geo, true);
  const res = send(s, 'loan_limit', 'GET /api/loans/limit', 'GET',
    BASE_URL + '/api/loans/limit' + qs({ propertyId: id }), null, true, OK_LOAN);
  if (res.status === 422) ltRejected.add(1, { ep: 'loan_limit' });
  validate('loan_limit', res, function (b) {
    return b.success === true && typeof b.data.finalLimit === 'number' && Array.isArray(b.data.missingFields);
  });
}

function reanalyze(s, geo) {
  // POST /api/properties/{id}/risk/reanalyze — 인증 필수. 매물 단위 최소 간격(잠정 10분) 안이면 429
  // RISK_REANALYZE_TOO_SOON 이 정상 응답이다. 락 대기 상한 초과는 503(5xx 로 센다).
  const id = pickPropertyId(geo, false);
  const res = send(s, 'reanalyze', 'POST /api/properties/{id}/risk/reanalyze', 'POST',
    BASE_URL + '/api/properties/' + id + '/risk/reanalyze', null, true, OK_REANALYZE);
  if (res.status === 429) ltRejected.add(1, { ep: 'reanalyze' });
  validate('reanalyze', res, function (b) {
    return (res.status === 200 && b.success === true && typeof b.data.riskGrade === 'string') ||
      (res.status === 429 && b.error.code === 'RISK_REANALYZE_TOO_SOON');
  });
}

// ── 반복 한 번 ────────────────────────────────────────────────────────────

/**
 * 조합에서 하나를 고른다. 재발급(2%)은 그 계정의 소유자 VU 만 할 수 있으므로(tokens.js), 소유자는 재발급을
 * 2% × (활성 VU / 활성 소유자) 확률로 고르고, 나머지는 재발급을 뺀 조합에서 고른다. 전체로 보면 재발급이 2%,
 * 나머지 엔드포인트도 원래 비중이 된다. 실제 비중은 요약의 ep 별 http_reqs 로 확인한다.
 */
function pickEndpoint(s, poolSize) {
  const active = Math.max(exec.instance.vusActive, 1);
  const owners = Math.max(Math.min(poolSize, active), 1);
  if (s.owner && !s.broken) {
    const p = Math.min(1, (WEIGHTS.reissue / 100) * (active / owners));
    if (Math.random() < p) return 'reissue';
  }
  const rest = Object.assign({}, WEIGHTS);
  delete rest.reissue;
  return pickWeighted(rest);
}

/**
 * 프로파일 스크립트의 default 함수가 부른다.
 *   data    — setup() 반환값 { geo, ... }
 *   pool    — 토큰 풀(SharedArray)
 *   phaseOf — 시나리오 시작 후 경과 초 → { phase, warmup }. 태그로 붙어 단계별 · 워밍업 제외 집계가 된다.
 */
export function iterate(data, pool, phaseOf) {
  const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
  const ph = phaseOf(elapsed);
  exec.vu.metrics.tags.phase = ph.phase;
  exec.vu.metrics.tags.warmup = ph.warmup ? 'true' : 'false';

  if (vu.filter === null) vu.filter = pickFilter();
  const s = getSession(pool);
  const geo = data.geo;

  let ep = pickEndpoint(s, pool.length);
  if (ep === 'map') ep = pickWeighted(MAP_STAGES);

  switch (ep) {
    case 'map_s1': districtCounts(s, 'map_s1'); break;
    case 'map_s2': mapStage2(s, geo); break;
    case 'map_s3': mapStage3(s, geo); break;
    case 'district_counts': districtCounts(s, 'district_counts'); break;
    case 'prop_detail': propDetail(s, geo); break;
    case 'risk': risk(s, geo); break;
    case 'prop_list': propList(s, geo); break;
    case 'wishlist_get': wishlistGet(s); break;
    case 'noti_list': notiList(s); break;
    case 'wishlist_write': wishlistWrite(s, geo); break;
    case 'reissue': doReissue(s); break;
    case 'loan_limit': loanLimit(s, geo); break;
    case 'reanalyze': reanalyze(s, geo); break;
    default: throw new Error('알 수 없는 ep: ' + ep);
  }

  if (THINK_MAX > 0) sleep(THINK_MIN + Math.random() * (THINK_MAX - THINK_MIN));
}

/** 단계가 하나인 프로파일(T1 · T3)의 phaseOf. */
export function singlePhase(name) {
  return function (elapsed) {
    return { phase: name, warmup: elapsed < WARMUP_SEC };
  };
}

/**
 * 단계별 임계 — 요약(handleSummary)에 단계 × 엔드포인트 수치가 남게 하려고 건다. 워밍업은 뺀다.
 * 판정 기준은 시험 계획서 2.3: p95 500ms, 5xx 1%. dropped_iterations 는 0 이어야 한다(8장 — 0 이 아닌 구간은 결과에서 제외).
 * 임계 실패는 시험을 멈추지 않는다(abortOnFail 없음). k6 종료 코드 99 로만 드러난다.
 */
export function buildThresholds(phases) {
  const t = { dropped_iterations: ['count<1'] };
  phases.forEach(function (p) {
    const sel = 'phase:' + p + ',warmup:false';
    t['http_req_duration{' + sel + '}'] = ['p(95)<500'];
    t['http_reqs{' + sel + '}'] = ['count>=0'];
    t['http_req_failed{' + sel + '}'] = ['rate<0.01'];
    t['lt_5xx{' + sel + '}'] = ['rate<0.01'];
    t['lt_valid{' + sel + '}'] = ['rate>0.99'];
    EP_TAGS.forEach(function (ep) {
      t['http_req_duration{ep:' + ep + ',' + sel + '}'] = ['p(95)<500'];
      t['http_reqs{ep:' + ep + ',' + sel + '}'] = ['count>=0'];
      t['lt_valid{ep:' + ep + ',' + sel + '}'] = ['rate>=0'];
    });
  });
  return t;
}

export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'];
