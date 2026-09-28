// C-2 입구 TLS 비용 — 시험 계획서 9장 C-2 · 보안 · 암호화 설계서 4.1. 스킬 crypto-cost-check 3장(HTTPS 함정).
//
// 무엇을 재나 — 새 연결 한 번이 입구 노드(APP-01 앞단 Nginx)에 주는 비용. TLS 핸드셰이크의 서명은 새 연결마다 한 번이라,
// 연결을 재사용하면(k6 기본 keep-alive) 거의 일어나지 않는다. 그래서 NEW_CONN=1(기본)이면 요청마다 새 연결을 연다.
// 대상은 정적 화면 경로 `/` 다 — 앞단 요청 상한(/api/ 의 60 r/s)이 걸리지 않고 앱 · DB 를 거의 거치지 않아, 차이가
// 입구 비용만으로 난다. 서비스 전체의 영향(API p95)은 이 스크립트가 아니라 steady.js 를 같은 조건으로 돌려 본다.
//
// 계단 — RATES(기본 50,100,200,400 RPS) 각 HOLD_SEC(기본 120초) 유지 · 30초 휴지. 단계 첫 30초는 워밍업으로 표시한다.
// 노드 CPU 는 여기서 재지 않는다 — Grafana(node exporter)에서 단계 시각 구간으로 조회한다. 이 스크립트는 시작 시각과
// 단계 경계를 요약에 남긴다(phase 태그 · 요약 파일 이름의 시각).
//
// IP 인증서는 공인 IP 이름이고 부하 생성기는 사설 주소(10.20.0.10)로 붙으므로 인증서 이름 검증을 끈다(insecureSkipTLSVerify).
// 운영 설정과 다른 점이다 — 검증을 끄는 것은 클라이언트 쪽 비용만 줄이고 서버의 서명 비용은 그대로다.
//
//   k6 run -e BASE_URL=http://10.20.0.10  -e LABEL=http-new      -e SUMMARY_DIR=results tls.js   ← HTTPS 전환 전 기준선만. 전환 뒤 80 은 301 을
//                                                                                         내고 이 스크립트는 넘기기를 따라가지 않으므로(redirects: 0) 301 을 잰다
//   k6 run -e BASE_URL=https://10.20.0.10 -e LABEL=https-ecdsa   -e SUMMARY_DIR=results tls.js
//   k6 run -e BASE_URL=https://10.20.0.10 -e LABEL=https-reuse -e NEW_CONN=0 -e SUMMARY_DIR=results tls.js

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { makeHandleSummary } from './lib/summary.js';

const BASE_URL = (__ENV.BASE_URL || 'https://10.20.0.10').replace(/\/+$/, '');
const LABEL = __ENV.LABEL || 'tls';
const NEW_CONN = __ENV.NEW_CONN !== '0';
const RATES = (__ENV.RATES || '50,100,200,400').split(',').map(function (v) { return Number(v.trim()); });
if (!RATES.every(function (r, i) { return Number.isInteger(r) && r > 0 && (i === 0 || r > RATES[i - 1]); })) {
  throw new Error('RATES 는 오름차순 양의 정수 목록 — 예: 50,100,200,400');
}
const HOLD_SEC = Number(__ENV.HOLD_SEC || 120);
const REST_SEC = 30;
const RAMP_SEC = 1;
const WARMUP_SEC = 30;
const PERIOD = HOLD_SEC + RAMP_SEC + REST_SEC + RAMP_SEC;

const lt5xx = new Rate('lt_5xx');

function phaseName(i) {
  return 's' + (i + 1) + '_' + RATES[i] + 'rps';
}
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
  if (into < HOLD_SEC || i === RATES.length - 1) {
    return { phase: phaseName(i), warmup: into < WARMUP_SEC };
  }
  return { phase: 'rest_' + (i + 1), warmup: true };
}

const thresholds = {};
PHASES.forEach(function (p) {
  thresholds['http_req_duration{phase:' + p + ',warmup:false}'] = ['p(95)<500'];
  thresholds['lt_5xx{phase:' + p + ',warmup:false}'] = ['rate<0.01'];
});

export const options = {
  scenarios: {
    c2: {
      executor: 'ramping-arrival-rate',
      startRate: RATES[0],
      timeUnit: '1s',
      stages: buildStages(),
      // 정적 파일 한 번 — 응답이 수 ms 라 VU 는 적게 든다. 포화 구간(응답 1초)까지 덮는다
      preAllocatedVUs: Math.max(20, Math.ceil(RATES[RATES.length - 1] * 0.2)),
      maxVUs: RATES[RATES.length - 1] * 2,
      gracefulStop: '10s',
    },
  },
  noConnectionReuse: NEW_CONN,
  insecureSkipTLSVerify: true,
  thresholds: thresholds,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const START = Date.now();

export default function () {
  const t = phaseOf((Date.now() - START) / 1000);
  const res = http.get(BASE_URL + '/', {
    tags: { ep: 'static', name: '/', phase: t.phase, warmup: String(t.warmup) },
    timeout: '10s',
    redirects: 0,
  });
  const bad = res.status === 0 || res.status >= 500;
  lt5xx.add(bad, { phase: t.phase, warmup: String(t.warmup) });
  check(res, { 'status 200': function (r) { return r.status === 200; } });
}

const PHASE_SECONDS = {};
PHASES.forEach(function (p) { PHASE_SECONDS[p] = HOLD_SEC - WARMUP_SEC; });
export const handleSummary = makeHandleSummary('tls', LABEL, PHASE_SECONDS);
