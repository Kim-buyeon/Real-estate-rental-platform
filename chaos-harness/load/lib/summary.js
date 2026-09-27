// 종료 요약 — 전체 요약 JSON 을 파일로 남기고, 화면에는 단계 × 엔드포인트 표만 찍는다.
// 원격 jslib(k6-summary)를 쓰지 않는다 — 부하 생성 노드가 인터넷에 못 나갈 수 있다.
//
// 파일: ${SUMMARY_DIR}/<스크립트>-<프로파일>-<시각>.json. SUMMARY_DIR 는 미리 만들어 둔다(없으면 현재 디렉터리).

function parseTags(key) {
  // 'http_req_duration{ep:risk,phase:t1,warmup:false}' → { metric, tags }
  const i = key.indexOf('{');
  if (i < 0) return { metric: key, tags: {} };
  const tags = {};
  key.slice(i + 1, -1).split(',').forEach(function (kv) {
    const j = kv.indexOf(':');
    tags[kv.slice(0, j)] = kv.slice(j + 1);
  });
  return { metric: key.slice(0, i), tags: tags };
}

function fmt(v, digits) {
  if (v === undefined || v === null || isNaN(v)) return '-';
  return Number(v).toFixed(digits === undefined ? 1 : digits);
}

function pad(s, n) {
  s = String(s);
  while (s.length < n) s += ' ';
  return s;
}

/**
 * phaseSeconds — { phase: 워밍업 제외 집계 구간 길이(초) } 실제 처리량(RPS)을 계산하는 데 쓴다.
 */
function table(data, phaseSeconds) {
  const m = data.metrics;
  const rows = {};
  Object.keys(m).forEach(function (key) {
    const p = parseTags(key);
    if (p.tags.warmup !== 'false' || !p.tags.phase) return;
    const rk = p.tags.phase + '|' + (p.tags.ep || '(전체)');
    rows[rk] = rows[rk] || { phase: p.tags.phase, ep: p.tags.ep || '(전체)' };
    const v = m[key].values;
    if (p.metric === 'http_req_duration') {
      rows[rk].p95 = v['p(95)'];
      rows[rk].p99 = v['p(99)'];
    } else if (p.metric === 'http_reqs') {
      rows[rk].count = v.count;
    } else if (p.metric === 'lt_valid') {
      rows[rk].valid = v.rate;
    } else if (p.metric === 'lt_5xx') {
      rows[rk].r5xx = v.rate;
    }
  });
  const keys = Object.keys(rows).sort();
  const lines = [];
  lines.push(pad('phase', 14) + pad('ep', 18) + pad('reqs', 9) + pad('rps', 8) + pad('p95ms', 9) +
    pad('p99ms', 9) + pad('valid%', 8) + '5xx%');
  keys.forEach(function (k) {
    const r = rows[k];
    const sec = phaseSeconds[r.phase];
    lines.push(pad(r.phase, 14) + pad(r.ep, 18) + pad(r.count === undefined ? '-' : r.count, 9) +
      pad(sec && r.count !== undefined ? fmt(r.count / sec, 2) : '-', 8) +
      pad(fmt(r.p95), 9) + pad(fmt(r.p99), 9) +
      pad(r.valid === undefined ? '-' : fmt(r.valid * 100, 2), 8) +
      (r.r5xx === undefined ? '-' : fmt(r.r5xx * 100, 2)));
  });
  return lines.join('\n');
}

export function makeHandleSummary(script, profile, phaseSeconds) {
  return function (data) {
    const ts = new Date().toISOString().replace(/[:.]/g, '-');
    const dir = __ENV.SUMMARY_DIR ? __ENV.SUMMARY_DIR.replace(/[\/\\]+$/, '') + '/' : '';
    const file = dir + script + '-' + profile + '-' + ts + '.json';
    const out = {};
    out[file] = JSON.stringify(data, null, 2);

    const m = data.metrics;
    const dropped = m.dropped_iterations ? m.dropped_iterations.values.count : 0;
    const expiredShared = m.lt_auth_expired_shared ? m.lt_auth_expired_shared.values.count : 0;
    const warns = [];
    if (dropped > 0) {
      warns.push('dropped_iterations=' + dropped + ' — 부하 생성기가 목표 RPS 를 못 냈다. 그 구간은 결과에서 제외한다' +
        '(트래픽 정의서 8장). 어느 구간인지는 --out csv/json 의 시계열로 찾는다.');
    }
    if (expiredShared > 0) {
      warns.push('lt_auth_expired_shared=' + expiredShared + ' — 토큰 풀이 VU 보다 작아 생긴 401 이다(서버 문제 아님).');
    }
    const sd = data.setup_data || {};
    if (sd.tokenReport && sd.tokenReport.warnings) sd.tokenReport.warnings.forEach(function (w) { warns.push(w); });
    if (sd.geo && sd.geo.fallbackDistricts && sd.geo.fallbackDistricts.length) {
      warns.push('대략 중심을 쓴 자치구: ' + sd.geo.fallbackDistricts.join(', '));
    }

    out.stdout = '\n== ' + script + ' (' + profile + ') — 워밍업 제외 ==\n' +
      (sd.geo ? '상위 자치구: ' + sd.geo.top.join(', ') + '\n' : '') +
      table(data, phaseSeconds) + '\n' +
      (warns.length ? '\n경고:\n- ' + warns.join('\n- ') + '\n' : '') +
      '\n요약 파일: ' + file + '\n';
    return out;
  };
}
