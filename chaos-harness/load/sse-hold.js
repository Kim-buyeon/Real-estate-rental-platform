// SSE 연결 점유 — xk6-sse 를 쓸 수 없을 때의 대안. 확장 없이 k6 기본 http 만 쓴다.
//
// 할 수 있는 것: 동시 연결 수만큼 Tomcat 스레드 · 앞단 연결을 붙잡는다(3.5 의 「스레드 점유」 · 「앱 정지 시 단절」).
// 할 수 없는 것: 이벤트 수신 시각 · 지연. k6 http 는 응답이 끝나야 본문을 돌려주므로, 서버가 연결을 닫은 뒤
//   (토큰 수명 만료, 최대 30분) 본문에서 하트비트 · 이벤트 수를 셀 뿐이다. 클라이언트 타임아웃이나 시험 종료로
//   끊기면 본문이 없다. 3.5 「수신 검증: 이벤트 도달 여부와 지연」은 이 스크립트로 충족되지 않는다 — sse.js 를 쓴다.
//
// 인증은 Bearer 헤더(명세상 허용). 계정 · 풀 규칙은 sse.js 와 같다.
//
//   k6 run -e SSE_VUS=250 -e DURATION=45m -e TOKENS=tokens-sse.json sse-hold.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { loadTokenPool, getSession, reissue, expiresWithin } from './lib/tokens.js';
import { BASE_URL } from './lib/mix.js';

const SSE_VUS = Number(__ENV.SSE_VUS || 250);
const DURATION = __ENV.DURATION || '45m';
const HEARTBEAT_SEC = Number(__ENV.HEARTBEAT_SEC || 25);

const POOL = loadTokenPool('tokens-sse', __ENV.TOKENS || './tokens-sse.json', function (p) { return open(p); });

const holdOk = new Rate('lt_sse_hold_ok');           // 서버가 정상 종료(200 + 본문)했는가
const heartbeatOk = new Rate('lt_sse_heartbeat_ok');
const events = new Counter('lt_sse_events');
const lifeSec = new Trend('lt_sse_conn_life_s');

export const options = {
  scenarios: { sse: { executor: 'constant-vus', vus: SSE_VUS, duration: DURATION, gracefulStop: '5s' } },
  setupTimeout: '1m',
  thresholds: { lt_sse_hold_ok: ['rate>0.99'], lt_sse_heartbeat_ok: ['rate>0.99'] },
};

export function setup() {
  if (POOL.length < SSE_VUS) throw new Error('SSE 토큰 풀이 SSE_VUS 보다 작다');
}

export default function () {
  const s = getSession(POOL);
  if (expiresWithin(s, 120)) {
    const r = reissue(s, BASE_URL, { tags: { ep: 'sse_reissue' }, timeout: '10s' });
    if (r.status !== 200) {
      sleep(5);
      return;
    }
  }
  const leftSec = s.accessExpSec - Math.floor(Date.now() / 1000);
  const started = Date.now();
  const res = http.get(BASE_URL + '/api/notifications/stream', {
    headers: { Accept: 'text/event-stream', Authorization: 'Bearer ' + s.accessToken },
    tags: { ep: 'sse_stream', name: 'GET /api/notifications/stream' },
    // 서버가 토큰 만료로 닫을 때까지 기다린다. 여유 60초
    timeout: (leftSec + 60) + 's',
  });
  const life = (Date.now() - started) / 1000;
  const ok = res.status === 200 && typeof res.body === 'string' && res.body.indexOf(':connected') >= 0;
  holdOk.add(ok);
  check(res, { 'sse 연결 유지 후 정상 종료': function () { return ok; } });
  if (ok) {
    lifeSec.add(life);
    const beats = (res.body.match(/^:heartbeat$/gm) || []).length;
    heartbeatOk.add(beats >= Math.max(Math.floor(life / HEARTBEAT_SEC) - 1, 0));
    const ev = res.body.match(/^event:.*$/gm) || [];
    ev.forEach(function (line) { events.add(1, { type: line.slice(6).trim() }); });
    sleep(0.2 + Math.random() * 0.8);
  } else {
    sleep(2 + Math.random() * 3);
  }
}
