// T2 계단식 — 10 → 25 → 50 → 100 → 200 RPS, 각 5분 유지 · 2분 휴지 (트래픽 정의서 4장 · 8장, ramping-arrival-rate).
// 포화점 실측. 판정은 시험 계획서 2.3 — 단계별 p95 500ms · 5xx 1% · 처리량 정체 중 먼저 오는 지점.
//
// 휴지 구간은 인가율 0 이다. 휴지의 목적이 앞 단계의 큐 · 커넥션 · GC 여파를 빼고 다음 단계를 독립적으로 재는
// 것이므로 요청을 넣지 않는다. 휴지 동안에도 SSE 연결(sse.js)은 유지된다.
// 단계 사이 전환은 1초 선형 변화다(0초 단계를 쓰지 않는다 — 실행기 버전마다 해석이 다를 수 있어 피했다).
// 총 길이: 5 × 5분 + 4 × 2분 + 전환 8초 ≈ 33분 8초. 문서의 「약 40분」과 차이 — 보고서 참조.
//
// 예비 측정(5.1 1번, 50 RPS 까지)은 MAX_STEP=3 으로 앞 세 단계만 돈다.
//
//   k6 run -e SUMMARY_DIR=results steps.js
//   k6 run -e MAX_STEP=3 -e SUMMARY_DIR=results steps.js

import { loadTokenPool, inspectPool } from './lib/tokens.js';
import { prepareGeo, iterate, buildThresholds, vuBudget, SUMMARY_TREND_STATS, WARMUP_SEC } from './lib/mix.js';
import { makeHandleSummary } from './lib/summary.js';

const ALL_RATES = [10, 25, 50, 100, 200];
const MAX_STEP = Number(__ENV.MAX_STEP || ALL_RATES.length);
if (!(MAX_STEP >= 1 && MAX_STEP <= ALL_RATES.length)) throw new Error('MAX_STEP 은 1~5');
const RATES = ALL_RATES.slice(0, MAX_STEP);

const HOLD_SEC = 300;
const REST_SEC = 120;
const RAMP_SEC = 1;
// 단계 i 의 유지 구간 시작 = i × PERIOD
const PERIOD = HOLD_SEC + RAMP_SEC + REST_SEC + RAMP_SEC;
const TOTAL_SEC = (RATES.length - 1) * PERIOD + HOLD_SEC;

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

/** 경과 초 → 단계 태그. 유지 구간 밖(전환 · 휴지)은 rest_<직전 단계> 로 붙는다 — 거의 요청이 없고 집계에서 빠진다. */
function phaseOf(elapsed) {
  const i = Math.min(Math.floor(elapsed / PERIOD), RATES.length - 1);
  const into = elapsed - i * PERIOD;
  if (into < HOLD_SEC || i === RATES.length - 1) {
    return { phase: phaseName(i), warmup: into < WARMUP_SEC };
  }
  return { phase: 'rest_' + (i + 1), warmup: true };
}

const POOL = loadTokenPool('tokens', __ENV.TOKENS || './tokens.json', function (p) { return open(p); });
const VUS = vuBudget(RATES[RATES.length - 1]);

export const options = {
  scenarios: {
    t2: {
      executor: 'ramping-arrival-rate',
      startRate: RATES[0],
      timeUnit: '1s',
      stages: buildStages(),
      preAllocatedVUs: VUS.preAllocatedVUs,
      maxVUs: VUS.maxVUs,
      gracefulStop: '15s',
    },
  },
  setupTimeout: '5m',
  thresholds: buildThresholds(PHASES),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  return {
    geo: prepareGeo(),
    tokenReport: inspectPool(POOL, VUS.maxVUs, TOTAL_SEC),
    schedule: { rates: RATES, holdSec: HOLD_SEC, restSec: REST_SEC, totalSec: TOTAL_SEC },
  };
}

export default function (data) {
  iterate(data, POOL, phaseOf);
}

const phaseSeconds = {};
PHASES.forEach(function (p) { phaseSeconds[p] = HOLD_SEC - WARMUP_SEC; });
export const handleSummary = makeHandleSummary('steps', 'T2', phaseSeconds);
