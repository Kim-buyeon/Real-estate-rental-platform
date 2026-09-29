// 성능 카드 — 엔드포인트 하나만 단독으로 계단 부하(시험 계획서 7.3 · 이슈 #290).
//
// 섞은 부하(T2)는 1위 병목에 가려 다른 경로의 한계가 안 보인다. 여기서는 EP 하나만 쳐서 그 경로의 기준 응답 · 단독 한계를
// 잰다. 요청 모양은 lib/mix.js 의 것을 그대로 쓴다(iterate 의 fixedEp) — 조합 부하와 같은 요청이다. think time 은 두지 않는다.
// markers(map_s3) 는 2단계(map-clusters)를 섞지 않는다 — setup 이 시드 매물의 좌표를 상세 조회로 받아 VU 의 seen 을 미리 채운다(9/28 A-markers 에서 약 9% 섞였다).
// DB · Redis 통계는 chaos-harness/report/collect-card.sh 가 회차 전후로 뜬다(요청당 질의 수 = 질의 통계 호출 수 ÷ 이 요청 수).
//
//   k6 run -e EP=clusters -e LABEL=clusters -e TOKENS=tokens.json -e SUMMARY_DIR=results card.js
//   k6 run -e EP=detail -e DIST=hot -e LABEL=detail-hot -e RATES=50 -e HOLD_SEC=90 ... card.js
//   k6 run -e EP=login -e LT_PASSWORD=$LT_PASSWORD -e TOKENS=tokens.json ... card.js   (끝나면 make-tokens.js 를 다시 돈다)
//
// EP — A 핵심 조회: clusters(지도 2단계 묶음 GET /api/properties/map-clusters) · markers(지도 3단계 반경 GET /api/properties)
//      · district(GET /api/properties/district-counts) · detail(GET /api/properties/{id}) · risk(GET /api/properties/{id}/risk)
//      · list(목록 첫 페이지) · listdeep(목록을 nextCursor 로 DEPTH 페이지까지 — 반복 하나가 요청 DEPTH 건)
//    B 나머지 경로 — mix.js 의 요청 그대로: wishlist(GET /api/me/wishlist) · noti(GET /api/notifications)
//      · wishwrite(POST /api/me/wishlist 201|409 · DELETE /api/me/wishlist/{id} 204 — 등록 · 해제가 반복마다 번갈아 한 건)
//      · reissue(POST /api/auth/reissue) · loan(GET /api/loans/limit 200|422) · reanalyze(POST /api/properties/{id}/risk/reanalyze 200|429)
//    B 여기서 쓰는 것: registry(GET /api/properties/{id}/registry — RISK-07) · ledger(GET /api/properties/{id}/ledger — PROP-04)
//      · login(POST /api/auth/login — USER-02, 비밀번호 해싱 한 번)
//    C 파급 경로: readall(PATCH /api/notifications/read-all) · profile(GET 뒤 같은 값 PUT /api/me/profile)
//      · subs(GET 뒤 같은 값 PUT /api/me/notification-subscriptions) — 운영 데이터를 바꾸지 않게 같은 값으로 다시 쓴다.
//    뺀 것(#290 계획): password-reset — 노드가 실제 메일 발송(EXTERNAL_MAIL_MODE=real)이라 부하가 실제 메일이 된다.
//      logout — 리프레시 토큰을 폐기해 토큰 풀이 소진된다. 로그인 비용은 login 회차가 따로 잰다.
//      판정 기준 변경 — 관리자 계정이 시드에 없다.
//
// B 회차의 부작용 — 코드에서 확인한 것(UserCommandService · RefreshTokenStore · WishlistCommandService · RiskReanalysis*):
//   reissue   리프레시 토큰은 사용자당 하나이고 재발급이 덮어쓴다(회전). mix.js 의 doReissue 는 소유자를 가리지 않으므로, 슬롯을
//             나눠 쓰는 VU(번호 > 풀 크기)가 생기면 서로의 토큰을 밀어내 401 이 연쇄한다(측정이 아니라 풀의 문제). 그래서 이 EP 는
//             VU 를 풀 크기 이하로 묶는다 — 모든 VU 가 자기 계정의 소유자이고, 받은 새 토큰을 자기 세션에 다시 넣는다(tokens.js reissue).
//             응답이 (풀 ÷ 목표 RPS)초를 넘으면 VU 가 모자라 dropped_iterations 로 드러난다 — 그 단계는 이미 포화다.
//             DB 는 사용자 한 건 조회, Redis 는 GET · SET 한 번씩. 끝나면 파일의 리프레시 토큰은 쓸 수 없다(원래 규칙대로 다시 만든다).
//   login     로그인도 같은 키를 덮어쓴다 — 풀의 리프레시 토큰이 그 순간 무효가 된다. 이 회차는 재발급을 하지 않아 스스로는 깨지지
//             않지만, **같은 풀을 쓰는 다른 k6 와 동시에 돌리지 않고 끝나면 make-tokens.js 를 다시 돈다.** 액세스 토큰은 무효화되지
//             않는다. 쓰기는 user_auth.last_login_at UPDATE 한 건과 Redis SET 한 건. 계정 잠금 · 시도 횟수 제한은 서버에 없다.
//             계정은 토큰 파일의 email(make-tokens.js 가 남긴다), LOGIN_START · LOGIN_COUNT 를 주면 그 구간(make-tokens 의 이메일
//             규칙 — 이미 가입돼 있어야 한다). 비밀번호는 LT_PASSWORD. 응답의 토큰은 버린다.
//   wishwrite VU 마다 자기가 201 로 등록한 매물만 해제하고 3건을 넘기지 않는다. 회차가 끝날 때 VU 마다 최대 3건이 남는다 — 시험 계정
//             (토큰 풀) 목록에만 남고 다음 make-tokens.js 가 지우지 않는다. 같은 계정을 나눠 쓰는 VU 끼리 같은 매물을 동시에 넣으면
//             유일 제약이 막아 409 가 되고 앱 로그에 경고(스택 포함)가 남는다.
//   reanalyze 매물 단위 최소 간격(10분)이 끝난 뒤에 간격 키를 건다. 시드 매물은 수백 건이라 단계가 지날수록 거의 전부 429 가 된다 —
//             이 카드는 「간격 확인 경로」의 비용이다. 200 인 요청은 분산 락 안에서 등기를 다시 떼고(mock — 같은 매물은 같은 등기라
//             변동 없음) 분석한다(결론이 같으면 저장 안 함). 입력이 그대로면 등급 · 등기 변동 이벤트가 없어 비동기 알림도 없다. 외부로
//             나가는 호출은 없다(등기 · 대장 mock). 끝난 뒤 10분 안에 다시 돌리면 처음부터 429 다.
//   registry · ledger  아직 수집하지 않은 매물이면 첫 조회가 mock 등기 · 대장을 떼어 저장한다(보관이 원래 동작 — 같은 내용). 이미 수집된
//             매물은 읽기만 한다. 시드 매물은 앞선 위험도 조회 회차로 대부분 수집돼 있다.
//
// DIST — uniform(25개 자치구 균등 · 구마다 시드 매물) | hot(매물 수 1위 자치구 하나 · 매물 5건, 지도 3단계(markers)는 40건) | 기본(mix.js — 상위 3구 60 %)
// CONTEND=1 — 모든 VU 가 토큰 풀의 첫 계정 하나로(쓰기 경합 · 같은 사용자 행). login 은 첫 계정 하나로 로그인한다.
//   mix.js 의 EP 는 세션을 mix.js 가 잡아 이 설정을 따르지 않으므로 받지 않는다
// RATES(기본 10,25,50,100,150) · HOLD_SEC(기본 120) — 단계마다 유지, 휴지 30초, 첫 30초는 워밍업(집계 제외). 첫 단계 = 기준 응답.

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';
import { loadTokenPool, inspectPool, getSession, authHeaders } from './lib/tokens.js';
import { prepareGeo, iterate, pickPropertyId, matchesFilter, lt5xx, FILTER_PRESETS, BASE_URL, REQ_TIMEOUT_SEC } from './lib/mix.js';
import { makeHandleSummary } from './lib/summary.js';

const EP = __ENV.EP;
const MIX_EP = {
  clusters: 'map_s2', markers: 'map_s3', district: 'district_counts', detail: 'prop_detail', risk: 'risk', list: 'prop_list',
  wishlist: 'wishlist_get', noti: 'noti_list', wishwrite: 'wishlist_write', reissue: 'reissue', loan: 'loan_limit', reanalyze: 'reanalyze',
};
const OWN_EP = ['listdeep', 'readall', 'profile', 'subs', 'registry', 'ledger', 'login'];
if (!EP || !(MIX_EP[EP] || OWN_EP.indexOf(EP) >= 0)) {
  throw new Error('EP 가 필요하다 — ' + Object.keys(MIX_EP).concat(OWN_EP).join(' · '));
}
const LABEL = __ENV.LABEL || EP;
const DIST = __ENV.DIST || '';
const CONTEND = __ENV.CONTEND === '1';
if (CONTEND && MIX_EP[EP]) throw new Error('CONTEND=1 은 mix.js 의 EP(' + Object.keys(MIX_EP).join(' · ') + ')에 걸리지 않는다');
const DEPTH = Number(__ENV.DEPTH || 5);
const RATES = (__ENV.RATES || '10,25,50,100,150').split(',').map(function (v) { return Number(v.trim()); });
if (!RATES.every(function (r, i) { return Number.isInteger(r) && r > 0 && (i === 0 || r > RATES[i - 1]); })) {
  throw new Error('RATES 는 오름차순 양의 정수 목록');
}
const HOLD_SEC = Number(__ENV.HOLD_SEC || 120);
const REST_SEC = 30;
const RAMP_SEC = 1;
const WARMUP_SEC = 30;
const PERIOD = HOLD_SEC + RAMP_SEC + REST_SEC + RAMP_SEC;

// 응답 크기(바이트) — data_received 는 단계로 나뉘지 않아 따로 둔다(카드 ⑧)
const respBytes = new Trend('lt_resp_bytes');

function phaseName(i) { return 's' + (i + 1) + '_' + RATES[i] + 'rps'; }
const PHASES = RATES.map(function (_, i) { return phaseName(i); });

function buildStages() {
  const stages = [];
  RATES.forEach(function (rate, i) {
    stages.push({ target: rate, duration: HOLD_SEC + 's' });
    if (i < RATES.length - 1) {
      stages.push({ target: 0, duration: RAMP_SEC + 's' });
      stages.push({ target: 0, duration: REST_SEC + 's' });
      stages.push({ target: RATES[i + 1], duration: RAMP_SEC + 's' });
    }
  });
  return stages;
}

function phaseOf(elapsed) {
  const i = Math.min(Math.floor(elapsed / PERIOD), RATES.length - 1);
  const into = elapsed - i * PERIOD;
  if (into < HOLD_SEC || i === RATES.length - 1) return { phase: phaseName(i), warmup: into < WARMUP_SEC };
  return { phase: 'rest_' + (i + 1), warmup: true };
}

// 요약 표(lib/summary.js)는 임계로 정의된 하위 지표만 단계별로 읽는다 — http_reqs 까지 단계별로 걸어 두어야
// 요청 수 · RPS 열이 채워진다(tls.js 에서 비었던 원인). 판정 기준은 시험 계획서 2.3.
const thresholds = { dropped_iterations: ['count<1'] };
PHASES.forEach(function (p) {
  const sel = 'phase:' + p + ',warmup:false';
  thresholds['http_req_duration{' + sel + '}'] = ['p(95)<500'];
  thresholds['http_reqs{' + sel + '}'] = ['count>=0'];
  thresholds['http_req_failed{' + sel + '}'] = ['rate<0.01'];
  thresholds['lt_5xx{' + sel + '}'] = ['rate<0.01'];
  thresholds['lt_resp_bytes{' + sel + '}'] = ['avg>=0'];
  // 응답 크기 — 받은 바이트(헤더 포함)를 단계로 나눈다. phase 는 VU 태그라 data_received 에도 붙는다
  thresholds['data_received{' + sel + '}'] = ['count>=0'];
});

const POOL = loadTokenPool('tokens', __ENV.TOKENS || './tokens.json', function (p) { return open(p); });
const PEAK = RATES[RATES.length - 1];
// reissue 는 VU 를 풀 크기 이하로 묶는다 — 모든 VU 가 소유자여야 회전이 서로를 밀어내지 않는다(머리 주석)
const VU_CAP = EP === 'reissue' ? POOL.length : Infinity;
const MAX_VUS = Math.min(PEAK * (REQ_TIMEOUT_SEC + 1), VU_CAP);

// login 계정 — 토큰 파일의 email, 또는 LOGIN_START · LOGIN_COUNT 구간(make-tokens.js 의 이메일 규칙과 같다)
const LT_PASSWORD = __ENV.LT_PASSWORD;
function ltEmail(n) { return 'loadtest+' + String(n).padStart(3, '0') + '@rental.test'; }
const LOGIN_EMAILS = EP !== 'login' ? [] : (function () {
  if (!LT_PASSWORD) throw new Error('EP=login 은 LT_PASSWORD 가 필요하다');
  if (__ENV.LOGIN_START) {
    const start = Number(__ENV.LOGIN_START);
    const count = Number(__ENV.LOGIN_COUNT || 100);
    const out = [];
    for (let n = start; n < start + count; n++) out.push(ltEmail(n));
    return out;
  }
  const out = [];
  for (let i = 0; i < POOL.length; i++) if (POOL[i].email) out.push(POOL[i].email);
  if (out.length === 0) throw new Error('토큰 파일에 email 이 없다 — LOGIN_START · LOGIN_COUNT 로 계정 구간을 준다');
  return out;
})();

export const options = {
  // 앞단이 HTTPS 다(#288). IP 인증서는 공인 IP 이름이라 사설 주소로 붙으면 이름 검증이 실패한다 — 검증만 끈다
  insecureSkipTLSVerify: true,
  scenarios: {
    card: {
      executor: 'ramping-arrival-rate',
      startRate: RATES[0],
      timeUnit: '1s',
      stages: buildStages(),
      // think time 이 없어 VU ≈ RPS × 응답 시간. 포화(응답 수 초)까지 덮게 넉넉히
      preAllocatedVUs: Math.min(Math.max(10, Math.ceil(PEAK * 0.5)), MAX_VUS),
      maxVUs: MAX_VUS,
      gracefulStop: '15s',
    },
  },
  setupTimeout: '5m',
  thresholds: thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
};

export function setup() {
  const geo = prepareGeo();
  if (DIST === 'uniform') {
    geo.top = [];                       // pickDistrict 가 25개 구를 균등하게 고른다
  } else if (DIST === 'hot') {
    const top = geo.districts.filter(function (d) { return d.name === geo.top[0]; });
    // 매물 5건으로 좁히는 것은 상세 · 위험 판정의 같은 행 쏠림용이다. 지도 3단계는 구 하나로 좁히는 것이 쏠림이라 시드를 남긴다 —
    // 5건이면 필터(전세만 등)에 맞는 시드가 없어 setup 이 멈췄다(9/29)
    const keep = EP === 'markers' ? 40 : 5;
    top.forEach(function (d) { d.ids = d.ids.slice(0, keep); d.safe = d.safe.slice(0, keep); });
    geo.districts = top;
    geo.top = [geo.top[0]];
  }
  if (EP === 'markers') seedPoints(geo);
  return { geo: geo, tokenReport: inspectPool(POOL, MAX_VUS, RATES.length * PERIOD) };
}

// markers 회차의 3단계 시드 — 구마다 시드 매물(d.ids, DIST 적용 뒤)을 상세 조회해 좌표 · 계약 형태 · 보증금을 받는다.
// prepareGeo 의 ids 는 목록 조회라 좌표가 없고, 묶음 조회의 칸 좌표는 평균이라 반경 안에 매물이 있다는 보장이 없다.
// 상세 좌표를 중심으로 같은 구 · 같은 필터로 반경 조회하면 최소 1건(그 매물)이다. 측정 대상이 아니다(setup 태그).
function seedPoints(geo) {
  const setupTags = { ep: 'setup', name: 'setup' };
  geo.districts.forEach(function (d) {
    const reqs = d.ids.map(function (id) {
      return { method: 'GET', url: BASE_URL + '/api/properties/' + id, params: { tags: setupTags, timeout: '60s' } };
    });
    const points = [];
    http.batch(reqs).forEach(function (res) {
      if (res.status !== 200) return;
      let b;
      try { b = res.json().data; } catch (e) { return; }
      if (!b || b.latitude === null || b.longitude === null || b.district !== d.name) return;
      points.push({
        id: b.propertyId,
        lat: Number(b.latitude),
        lng: Number(b.longitude),
        district: b.district,
        safe: !!(b.riskSummary && b.riskSummary.riskGrade === 'SAFE'),
        contractType: b.contractType,
        deposit: b.deposit,
      });
    });
    d.points = points;
  });
  // 필터 조합마다 맞는 시드가 하나는 있어야 한다 — 없으면 그 필터를 받은 VU 가 멈춘다(mix.js mapStage3)
  FILTER_PRESETS.forEach(function (f) {
    const n = geo.districts.reduce(function (a, d) {
      return a + d.points.filter(function (p) { return matchesFilter(p, f.params); }).length;
    }, 0);
    if (n === 0) throw new Error('setup: 필터 ' + JSON.stringify(f.params) + ' 에 맞는 시드 좌표가 없다');
  });
  const total = geo.districts.reduce(function (a, d) { return a + d.points.length; }, 0);
  console.log('[card] markers 시드 좌표 ' + total + '건 · 구 ' + geo.districts.length + '개');
}

function session() {
  if (!CONTEND) return getSession(POOL);
  // 모든 VU 가 첫 계정 — getSession 의 소유자 개념을 쓰지 않고 헤더만 빌린다(재발급은 하지 않는다 — 측정이 짧다)
  return { accessToken: POOL[0].accessToken, owner: false, broken: false };
}

function req(s, method, path, payload, name) {
  const params = { tags: { ep: EP, name: name }, timeout: REQ_TIMEOUT_SEC + 's', headers: s ? Object.assign({}, authHeaders(s)) : {} };
  if (payload !== null) params.headers['Content-Type'] = 'application/json';
  const res = http.request(method, BASE_URL + path, payload === null ? null : JSON.stringify(payload), params);
  lt5xx.add(res.status >= 500 || res.status === 0, { ep: EP });
  respBytes.add(res.body ? res.body.length : 0);
  return res;
}

function ok(res, label, expected) {
  const sets = {};
  sets[label] = function (r) { return expected.indexOf(r.status) >= 0; };
  return check(res, sets);
}

function own(data) {
  if (EP === 'login') {
    // 인증 헤더 없이 보낸다(공개). 응답 토큰은 쓰지 않는다 — 풀의 세션과 섞지 않는다
    const email = CONTEND ? LOGIN_EMAILS[0] : LOGIN_EMAILS[Math.floor(Math.random() * LOGIN_EMAILS.length)];
    const res = req(null, 'POST', '/api/auth/login', { email: email, password: LT_PASSWORD }, 'POST /api/auth/login');
    if (ok(res, 'login 200', [200])) {
      check(res, { 'login 토큰': function (r) { const d = r.json().data; return typeof d.accessToken === 'string' && typeof d.refreshToken === 'string'; } });
    }
    return;
  }
  const s = session();
  if (EP === 'registry') {
    // 응답 data { propertyId, ownerships[], mortgages[], collectedAt, dataSource } — 위험도 명세 1.3
    const id = pickPropertyId(data.geo, false);
    const res = req(s, 'GET', '/api/properties/' + id + '/registry', null, 'GET /api/properties/{id}/registry');
    if (ok(res, 'registry 200', [200])) {
      check(res, { 'registry 응답': function (r) { const d = r.json().data; return d.propertyId === id && Array.isArray(d.ownerships) && Array.isArray(d.mortgages); } });
    }
  } else if (EP === 'ledger') {
    // 응답 data { propertyId, mainPurpose, ... } — LedgerResponse
    const id = pickPropertyId(data.geo, false);
    const res = req(s, 'GET', '/api/properties/' + id + '/ledger', null, 'GET /api/properties/{id}/ledger');
    if (ok(res, 'ledger 200', [200])) {
      check(res, { 'ledger 응답': function (r) { return r.json().data.propertyId === id; } });
    }
  } else if (EP === 'readall') {
    ok(req(s, 'PATCH', '/api/notifications/read-all', null, 'PATCH /api/notifications/read-all'), 'readall 200', [200]);
  } else if (EP === 'profile') {
    const g = req(s, 'GET', '/api/me/profile', null, 'GET /api/me/profile');
    if (!ok(g, 'profile GET 200', [200])) return;
    const d = g.json().data;
    const body = { account: { name: d.account.name, phone: d.account.phone }, profile: d.profile };
    ok(req(s, 'PUT', '/api/me/profile', body, 'PUT /api/me/profile'), 'profile PUT 200', [200]);
  } else if (EP === 'subs') {
    const g = req(s, 'GET', '/api/me/notification-subscriptions', null, 'GET /api/me/notification-subscriptions');
    if (!ok(g, 'subs GET 200', [200])) return;
    ok(req(s, 'PUT', '/api/me/notification-subscriptions', g.json().data, 'PUT /api/me/notification-subscriptions'), 'subs PUT 200', [200]);
  } else if (EP === 'listdeep') {
    const geo = data.geo;
    const d = geo.districts[Math.floor(Math.random() * geo.districts.length)];
    let cursor = null;
    for (let i = 0; i < DEPTH; i++) {
      const q = '?district=' + encodeURIComponent(d.name) + '&size=20' + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '');
      const res = req(s, 'GET', '/api/properties' + q, null, 'GET /api/properties (list page)');
      if (!ok(res, 'listdeep 200', [200])) return;
      const b = res.json().data;
      if (!b.hasNext || !b.nextCursor) return;
      cursor = b.nextCursor;
    }
  }
}

export default function (data) {
  if (MIX_EP[EP]) {
    iterate(data, POOL, phaseOf, MIX_EP[EP]);
    return;
  }
  const ph = phaseOf((Date.now() - exec.scenario.startTime) / 1000);
  exec.vu.metrics.tags.phase = ph.phase;
  exec.vu.metrics.tags.warmup = ph.warmup ? 'true' : 'false';
  own(data);
}

const PHASE_SECONDS = {};
PHASES.forEach(function (p) { PHASE_SECONDS[p] = HOLD_SEC - WARMUP_SEC; });
export const handleSummary = makeHandleSummary('card', LABEL, PHASE_SECONDS);
