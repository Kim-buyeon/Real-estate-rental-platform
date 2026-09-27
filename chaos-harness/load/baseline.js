// T1 기준선 — 10 RPS 고정 10분 (트래픽 정의서 4장 · 8장, constant-arrival-rate).
// 정상 상태 확인 · 회귀 비교의 기준값. 프로파일을 즉석으로 바꾸지 않는다(8장) — 인가량과 길이는 여기 고정이다.
//
//   k6 run -e BASE_URL=http://10.20.0.10 -e SUMMARY_DIR=results baseline.js

import { loadTokenPool, inspectPool } from './lib/tokens.js';
import { prepareGeo, iterate, singlePhase, buildThresholds, vuBudget, SUMMARY_TREND_STATS, WARMUP_SEC } from './lib/mix.js';
import { makeHandleSummary } from './lib/summary.js';

const RATE = 10;
const DURATION_SEC = 600;
const PHASE = 't1_10rps';

const POOL = loadTokenPool('tokens', __ENV.TOKENS || './tokens.json', function (p) { return open(p); });
const VUS = vuBudget(RATE);

export const options = {
  scenarios: {
    t1: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION_SEC + 's',
      preAllocatedVUs: VUS.preAllocatedVUs,
      maxVUs: VUS.maxVUs,
      gracefulStop: '15s',
    },
  },
  setupTimeout: '5m',
  thresholds: buildThresholds([PHASE]),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  return {
    geo: prepareGeo(),
    tokenReport: inspectPool(POOL, VUS.maxVUs, DURATION_SEC),
  };
}

const phaseOf = singlePhase(PHASE);

export default function (data) {
  iterate(data, POOL, phaseOf);
}

const phaseSeconds = {};
phaseSeconds[PHASE] = DURATION_SEC - WARMUP_SEC;
export const handleSummary = makeHandleSummary('baseline', 'T1', phaseSeconds);
