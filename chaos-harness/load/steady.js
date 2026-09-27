// T3 정상 부하 — 25 RPS 고정, 길이는 시험에 맞춘다 (트래픽 정의서 4장 · 8장, constant-arrival-rate).
// 장애 주입의 배경 부하이며 T5 · T6 · T7 도 이 스크립트로 돈다(8장):
//   T5 캐시 콜드 — 직전에 redis-cli FLUSHALL
//   T6 배포 중   — 실행 중 deploy.sh
//   T7 배치 동시 — 실행 중 재분석 배치 수동 기동
// PROFILE 은 요약 파일 이름에만 쓴다(인가량은 바뀌지 않는다). DURATION 은 초 또는 k6 형식(예: 20m, 1h30m).
//
//   k6 run -e DURATION=20m -e PROFILE=T3 -e SUMMARY_DIR=results steady.js

import { loadTokenPool, inspectPool } from './lib/tokens.js';
import { prepareGeo, iterate, singlePhase, buildThresholds, vuBudget, SUMMARY_TREND_STATS, WARMUP_SEC } from './lib/mix.js';
import { makeHandleSummary } from './lib/summary.js';

const RATE = 25;
const PROFILE = __ENV.PROFILE || 'T3';
const PHASE = 't3_25rps';

function toSeconds(s) {
  if (/^\d+$/.test(s)) return Number(s);
  const re = /(\d+)(h|m|s)/g;
  let total = 0;
  let match;
  let consumed = '';
  while ((match = re.exec(s)) !== null) {
    total += Number(match[1]) * (match[2] === 'h' ? 3600 : match[2] === 'm' ? 60 : 1);
    consumed += match[0];
  }
  if (consumed !== s || total === 0) throw new Error('DURATION 형식을 읽지 못했다: ' + s + ' (예: 600, 20m, 1h30m)');
  return total;
}

if (!__ENV.DURATION) {
  throw new Error('DURATION 이 필요하다 — T3 는 시험 길이에 맞춘다(트래픽 정의서 4장). 예: -e DURATION=20m');
}
const DURATION_SEC = toSeconds(__ENV.DURATION);
if (DURATION_SEC <= WARMUP_SEC) throw new Error('DURATION 은 워밍업(60초)보다 길어야 한다');

const POOL = loadTokenPool('tokens', __ENV.TOKENS || './tokens.json', function (p) { return open(p); });
const VUS = vuBudget(RATE);

export const options = {
  scenarios: {
    t3: {
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
export const handleSummary = makeHandleSummary('steady', PROFILE, phaseSeconds);
