// T4 지속 — 15 RPS 고정 · 2시간 (트래픽 정의서 4장 · 8장, constant-arrival-rate). 메모리 · 커넥션 누수 확인.
// think time 은 켠다(기본) — 2시간이라 액세스 토큰 만료가 실제로 일어나고, 조합의 토큰 갱신이 Redis 경로를 유지한다(트래픽 정의서 3.1 「토큰 갱신을 조합에 넣는 이유」).
// 판정은 응답 시간보다 **추이**다 — 노드 자원 기록(힙 · RSS · Hikari · PostgreSQL 연결 수)이 시간에 따라 계속 오르는지 본다.
// DURATION 을 주면 그 길이로 돈다(시험 준비 · 짧은 확인용). 기본 2h.
//
//   k6 run -e SUMMARY_DIR=results soak.js
//   k6 run -e DURATION=10m -e SUMMARY_DIR=results soak.js

import { loadTokenPool, inspectPool } from './lib/tokens.js';
import { prepareGeo, iterate, singlePhase, buildThresholds, vuBudget, SUMMARY_TREND_STATS, WARMUP_SEC } from './lib/mix.js';
import { makeHandleSummary } from './lib/summary.js';

const RATE = 15;
const PHASE = 't4_15rps';

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
  if (consumed !== s || total === 0) throw new Error('DURATION 형식을 읽지 못했다: ' + s + ' (예: 600, 20m, 2h)');
  return total;
}

const DURATION_SEC = toSeconds(__ENV.DURATION || '2h');
if (DURATION_SEC <= WARMUP_SEC) throw new Error('DURATION 은 워밍업(60초)보다 길어야 한다');

const POOL = loadTokenPool('tokens', __ENV.TOKENS || './tokens.json', function (p) { return open(p); });
const VUS = vuBudget(RATE);

export const options = {
  // 앞단이 HTTPS 다(2026-09-28 · #288). IP 인증서는 공인 IP 이름이라 사설 주소(10.20.0.10)로 붙으면 이름 검증이 실패한다 —
  // 검증만 끈다(서버의 TLS 비용은 그대로다). 운영 설정과 다른 점이다(스킬 crypto-cost-check 3장)
  insecureSkipTLSVerify: true,
  scenarios: {
    t4: {
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
export const handleSummary = makeHandleSummary('soak', 'T4', phaseSeconds);
