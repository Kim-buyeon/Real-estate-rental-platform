// SSE 동시 연결 — 트래픽 정의서 3.5 · 8장 (constant-vus). 전 프로파일과 병행하며, 먼저 띄워 연결을 확립한 뒤
// 본 프로파일을 인가한다. VU 하나 = 연결 하나.
//
// ── 필요한 것 ───────────────────────────────────────────────────────────
// k6 기본 http 는 응답이 끝나야 돌려주므로 SSE 이벤트를 받는 순간을 볼 수 없다. 커뮤니티 확장 xk6-sse
// (github.com/phymbert/xk6-sse)의 k6/x/sse 를 쓴다.
//   · k6 v2.x(≥ 2.0) + 자동 확장 해석: 이 import 만 있으면 k6 가 확장이 든 바이너리를 받아 온다
//     (Grafana 빌드 서비스에 나갈 수 있어야 한다). xk6-sse v0.2.0 이 k6 v2 대상이다.
//   · 인터넷이 없는 노드: xk6 build --with github.com/phymbert/xk6-sse@v0.2.0 으로 만든 바이너리를 쓴다
//     (k6 v1.x 라면 xk6-sse v0.1.12).
//   · 확장을 쓸 수 없으면 sse-hold.js — 연결 점유만 만들고 이벤트는 연결이 닫힌 뒤 본문으로 센다(지연 측정 불가).
//
// ── 서버 규약(알림 명세 1.1 · 1.4, NotificationStreamController · SseEmitterStore) ──────────────
//   · 연결: GET /api/notifications/stream, 인증은 Bearer 헤더 또는 ?ticket=(POST /api/notifications/stream-ticket 으로
//     받은 일회용 티켓, 30초). 화면(EventSource)은 티켓을 쓰므로 기본은 티켓이다. SSE_AUTH=header 로 헤더를 쓴다.
//   · 연결 직후 주석 :connected, 이후 주석 :heartbeat 를 전역 25초 간격(notification.sse.heartbeat-interval, 잠정)으로.
//   · 이벤트: event:RISK_CHANGE | REGISTRY_CHANGE, data {notificationId,type,propertyId,createdAt}.
//   · 연결 수명 = 인증에 쓴 액세스 토큰의 남은 시간(최대 30분). 서버가 닫으면 새 티켓으로 다시 연결한다.
//
// ── 계정 ────────────────────────────────────────────────────────────────
// 연결마다 30분 안에 재연결하며 재발급이 필요하므로 VU 마다 계정 하나를 따로 쓴다. 풀(tokens-sse.json)은
// SSE_VUS 이상이어야 하고, 본 시험의 풀(tokens.json)과 계정 구간이 겹치면 안 된다(make-tokens.js START).
//
//   k6 run -e SSE_VUS=250 -e DURATION=45m -e TOKENS=tokens-sse.json -e SUMMARY_DIR=results sse.js

import sse from 'k6/x/sse';
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { loadTokenPool, getSession, reissue, expiresWithin, inspectPool } from './lib/tokens.js';
import { BASE_URL } from './lib/mix.js';

// 동시 연결 — 3.5 「인가 RPS × 10 (T3 기준 250)」
const SSE_VUS = Number(__ENV.SSE_VUS || 250);
const DURATION = __ENV.DURATION || '45m'; // 본 프로파일보다 길게. T2 는 약 34분
const AUTH = (__ENV.SSE_AUTH || 'ticket').toLowerCase();
// 서버 하트비트 간격(잠정 25초). 누락 판정에만 쓴다.
const HEARTBEAT_SEC = Number(__ENV.HEARTBEAT_SEC || 25);

const POOL = loadTokenPool('tokens-sse', __ENV.TOKENS || './tokens-sse.json', function (p) { return open(p); });

const sseOpenMs = new Trend('lt_sse_open_ms', true);            // 요청 시작 → 스트림 열림
const sseConnected = new Rate('lt_sse_connected');              // :connected 를 받았는가
const sseHeartbeatGap = new Trend('lt_sse_heartbeat_gap_ms', true); // 하트비트 사이 간격(첫 것은 연결부터)
const sseHeartbeatOk = new Rate('lt_sse_heartbeat_ok');         // 연결 동안 기대한 하트비트 수를 받았는가
const sseEvents = new Counter('lt_sse_events');                 // 알림 이벤트 수 (tag type)
const sseEventLatency = new Trend('lt_sse_event_latency_ms', true); // 수신 시각 - createdAt (시계 차 포함)
const sseErrors = new Counter('lt_sse_errors');
const sseLifeSec = new Trend('lt_sse_conn_life_s');              // 연결 유지 시간. 짧으면 예상 밖 단절
const sseStatusOk = new Rate('lt_sse_status_ok');

export const options = {
  scenarios: {
    sse: {
      executor: 'constant-vus',
      vus: SSE_VUS,
      duration: DURATION,
      gracefulStop: '5s',
    },
  },
  setupTimeout: '2m',
  thresholds: {
    lt_sse_status_ok: ['rate>0.99'],
    lt_sse_connected: ['rate>0.99'],
    lt_sse_heartbeat_ok: ['rate>0.99'],
    lt_sse_open_ms: ['p(95)<1000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
};

export function setup() {
  if (POOL.length < SSE_VUS) {
    throw new Error('SSE 토큰 풀(' + POOL.length + ')이 SSE_VUS(' + SSE_VUS + ')보다 작다 — VU 마다 계정 하나가 필요하다');
  }
  if (AUTH !== 'ticket' && AUTH !== 'header') throw new Error('SSE_AUTH 는 ticket 또는 header');
  return { tokenReport: inspectPool(POOL, SSE_VUS, 0) };
}

function streamUrl(s) {
  if (AUTH === 'header') return { url: BASE_URL + '/api/notifications/stream', auth: true };
  const res = http.post(BASE_URL + '/api/notifications/stream-ticket', null, {
    headers: { Authorization: 'Bearer ' + s.accessToken },
    tags: { ep: 'sse_ticket', name: 'POST /api/notifications/stream-ticket' },
    timeout: '10s',
  });
  let ticket = null;
  try {
    ticket = res.json('data.ticket');
  } catch (e) {
    ticket = null;
  }
  check(res, { 'sse 티켓 발급': function () { return res.status === 200 && !!ticket; } });
  if (!ticket) return null;
  return { url: BASE_URL + '/api/notifications/stream?ticket=' + encodeURIComponent(ticket), auth: false };
}

export default function () {
  const s = getSession(POOL);

  // 남은 수명이 2분 미만이면 먼저 재발급 — 그대로 열면 곧 닫히는 연결을 연다
  if (expiresWithin(s, 120)) {
    const r = reissue(s, BASE_URL, { tags: { ep: 'sse_reissue', name: 'POST /api/auth/reissue' }, timeout: '10s' });
    if (r.status !== 200) {
      sseErrors.add(1, { stage: 'reissue' });
      sleep(5);
      return;
    }
  }

  const target = streamUrl(s);
  if (!target) {
    sseErrors.add(1, { stage: 'ticket' });
    sleep(2 + Math.random() * 3);
    return;
  }

  const headers = { Accept: 'text/event-stream' };
  if (target.auth) headers.Authorization = 'Bearer ' + s.accessToken;

  const started = Date.now();
  let openedAt = 0;
  let lastBeat = 0;
  let beats = 0;
  let connected = false;

  const res = sse.open(target.url, {
    method: 'GET',
    headers: headers,
    tags: { ep: 'sse_stream', name: 'GET /api/notifications/stream' },
  }, function (client) {
    // 'open' 은 응답 헤더를 받으면 상태 코드와 무관하게 온다(xk6-sse sse.go) — 401 봉투도 여기로 온다.
    // 그래서 열림 시간은 연결이 끝난 뒤 상태가 200 일 때만 기록한다.
    client.on('open', function () {
      openedAt = Date.now();
      lastBeat = openedAt;
    });

    client.on('event', function (ev) {
      const now = Date.now();
      if (ev.comment === 'connected') {
        connected = true;
        return;
      }
      if (ev.comment === 'heartbeat') {
        sseHeartbeatGap.add(now - lastBeat);
        lastBeat = now;
        beats++;
        return;
      }
      if (ev.name) {
        sseEvents.add(1, { type: ev.name });
        let ok = false;
        try {
          const d = JSON.parse(ev.data);
          ok = d.type === ev.name && typeof d.notificationId === 'number';
          const created = Date.parse(d.createdAt);
          if (!isNaN(created)) sseEventLatency.add(now - created, { type: ev.name });
        } catch (e) {
          ok = false;
        }
        check(ev, { 'sse 이벤트 형식': function () { return ok; } });
      }
    });

    client.on('error', function (e) {
      sseErrors.add(1, { stage: 'stream' });
      if (exec.vu.iterationInScenario < 2) console.warn('[sse] VU ' + exec.vu.idInTest + ' 오류: ' + e.error());
    });
  });

  // 여기 오면 연결이 닫혔다 — 서버가 수명 만료로 닫았거나, 오류이거나, 시험이 끝났다
  const status = res ? res.status : 0;
  sseStatusOk.add(status === 200);
  if (status === 200) {
    if (openedAt) sseOpenMs.add(openedAt - started);
    sseConnected.add(connected);
    const lifeMs = Date.now() - (openedAt || started);
    sseLifeSec.add(lifeMs / 1000);
    // 전역 고정 간격 스케줄러라 연결 직후 첫 하트비트까지 0~25초. 기대 수 = floor(유지 시간 / 간격) - 1 (여유 1)
    const expected = Math.max(Math.floor(lifeMs / 1000 / HEARTBEAT_SEC) - 1, 0);
    sseHeartbeatOk.add(beats >= expected);
    sleep(0.2 + Math.random() * 0.8); // 재연결. 화면은 즉시 새 티켓을 받는다
  } else {
    sleep(2 + Math.random() * 3); // 401 · 5xx · 연결 실패 — 잠깐 쉬고 다시
  }
}

export function handleSummary(data) {
  const ts = new Date().toISOString().replace(/[:.]/g, '-');
  const dir = __ENV.SUMMARY_DIR ? __ENV.SUMMARY_DIR.replace(/[\/\\]+$/, '') + '/' : '';
  const file = dir + 'sse-' + ts + '.json';
  const m = data.metrics;
  function v(name, stat) {
    return m[name] && m[name].values[stat] !== undefined ? m[name].values[stat] : '-';
  }
  const lines = [
    '',
    '== sse (' + SSE_VUS + ' VU, ' + AUTH + ') ==',
    '연결 성공률(200)        ' + v('lt_sse_status_ok', 'rate'),
    ':connected 수신률       ' + v('lt_sse_connected', 'rate'),
    '하트비트 충족률          ' + v('lt_sse_heartbeat_ok', 'rate'),
    '하트비트 간격 p95/max ms ' + v('lt_sse_heartbeat_gap_ms', 'p(95)') + ' / ' + v('lt_sse_heartbeat_gap_ms', 'max'),
    '열림 p95 ms             ' + v('lt_sse_open_ms', 'p(95)'),
    '연결 유지 med/min s      ' + v('lt_sse_conn_life_s', 'med') + ' / ' + v('lt_sse_conn_life_s', 'min'),
    '알림 이벤트 수           ' + v('lt_sse_events', 'count'),
    '이벤트 지연 p95 ms       ' + v('lt_sse_event_latency_ms', 'p(95)') + ' (서버 · 생성기 시계 차 포함)',
    '오류 수                 ' + v('lt_sse_errors', 'count'),
    '',
    '요약 파일: ' + file,
    '',
  ];
  const out = { stdout: lines.join('\n') };
  out[file] = JSON.stringify(data, null, 2);
  return out;
}
