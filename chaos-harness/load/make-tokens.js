// 토큰 풀 만들기 — 시험 계정 N 개를 가입시키고(이미 있으면 건너뛴다) 로그인해 토큰을 JSON 으로 남긴다.
// 본 시험의 측정에 들지 않는 준비 단계다. 로그인은 bcrypt 를 치므로 본 프로파일과 동시에 돌리지 않는다.
//
// 계정: loadtest+NNN@rental.test (NNN = START … START+COUNT-1, 세 자리 0 채움), 비밀번호: 환경 변수 LT_PASSWORD.
// 가입은 POST /api/auth/signup { email, password, name, phone? } → 201, 이미 있으면 409 USER_DUPLICATED(AuthController).
// 이메일 인증 단계는 없다 — 가입 즉시 로그인된다(UserCommandService.signUpWithEmail).
// 비밀번호 정책(@PasswordPolicy): 8~64자, UTF-8 72바이트 이하.
//
// 로그인할 때마다 그 계정의 리프레시 토큰이 새로 회전된다 — **본 시험을 돌릴 때마다 직전에 다시 실행한다.**
// 본 시험(tokens.json)과 SSE(tokens-sse.json)는 계정 구간을 나눈다. 같은 계정을 두 프로세스가 쓰면 재발급이
// 서로의 리프레시 토큰을 무효로 만든다.
//
//   k6 run -e LT_PASSWORD=... -e COUNT=100 -e OUT=tokens.json make-tokens.js
//   k6 run -e LT_PASSWORD=... -e START=1001 -e COUNT=250 -e OUT=tokens-sse.json make-tokens.js
//
// 결과 파일은 handleSummary 가 쓴다(k6 에서 파일을 쓰는 유일한 경로). 형식:
//   { generatedAt, baseUrl, start, count, tokens: [{ email, accessToken, refreshToken }], errors: [...] }

import http from 'k6/http';

const BASE_URL = (__ENV.BASE_URL || 'http://10.20.0.10').replace(/\/+$/, '');
const PASSWORD = __ENV.LT_PASSWORD;
const START = Number(__ENV.START || 1);
const COUNT = Number(__ENV.COUNT || 100);
const OUT = __ENV.OUT || 'tokens.json';
// 계정마다 미리 넣어 둘 관심 매물 수. GET /api/me/wishlist 의 「빈 배열 = 실패」 검증(3.4)이 성립하려면 1 이상.
const WISHLIST = Number(__ENV.WISHLIST || 5);
// 한 번에 보내는 요청 수(http.batch). 가입 · 로그인이 bcrypt 라 너무 올리면 앱 CPU 를 잠깐 포화시킨다.
const CONCURRENCY = Number(__ENV.CONCURRENCY || 10);

if (!PASSWORD) throw new Error('LT_PASSWORD 가 필요하다 (8~64자)');
if (PASSWORD.length < 8 || PASSWORD.length > 64) throw new Error('LT_PASSWORD 는 8~64자 — @PasswordPolicy');

export const options = {
  scenarios: { once: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '1m' } },
  setupTimeout: __ENV.SETUP_TIMEOUT || '30m',
  // 준비 단계라 임계를 걸지 않는다
};

function email(n) {
  return 'loadtest+' + String(n).padStart(3, '0') + '@rental.test';
}

function json(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

function errCode(res) {
  const b = json(res);
  return b && b.error ? b.error.code : null;
}

function post(url, body, token, tag) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  return {
    method: 'POST',
    url: BASE_URL + url,
    body: JSON.stringify(body),
    params: { headers: headers, tags: { ep: tag }, timeout: '60s' },
  };
}

function chunks(arr, n) {
  const out = [];
  for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n));
  return out;
}

export function setup() {
  const errors = [];
  const numbers = [];
  for (let n = START; n < START + COUNT; n++) numbers.push(n);

  // 관심 매물 후보 — 목록 조회(좌표 없음, 인증 선택). 실제로 있는 매물 ID 다.
  let candidateIds = [];
  if (WISHLIST > 0) {
    const res = http.get(BASE_URL + '/api/properties?size=100', { tags: { ep: 'prepare' }, timeout: '60s' });
    const b = json(res);
    if (res.status !== 200 || !b || !b.data || !Array.isArray(b.data.items) || b.data.items.length === 0) {
      throw new Error('매물 목록 조회 실패 status=' + res.status + ' — 매물이 적재돼 있는지 확인한다');
    }
    candidateIds = b.data.items.map(function (i) { return i.propertyId; });
  }

  // 1. 가입 — 201 이면 새 계정, 409 USER_DUPLICATED 면 이미 있음(로그인만)
  let created = 0;
  let existed = 0;
  chunks(numbers, CONCURRENCY).forEach(function (group) {
    const responses = http.batch(group.map(function (n) {
      return post('/api/auth/signup', { email: email(n), password: PASSWORD, name: '부하시험' + n }, null, 'signup');
    }));
    responses.forEach(function (res, i) {
      if (res.status === 201) created++;
      else if (res.status === 409 && errCode(res) === 'USER_DUPLICATED') existed++;
      else errors.push({ step: 'signup', email: email(group[i]), status: res.status, code: errCode(res) });
    });
  });

  // 2. 로그인 — 이미 있던 계정의 비밀번호가 LT_PASSWORD 와 다르면 401 AUTH_INVALID_CREDENTIAL
  const tokens = [];
  chunks(numbers, CONCURRENCY).forEach(function (group) {
    const responses = http.batch(group.map(function (n) {
      return post('/api/auth/login', { email: email(n), password: PASSWORD }, null, 'login');
    }));
    responses.forEach(function (res, i) {
      const b = json(res);
      if (res.status === 200 && b && b.data && b.data.accessToken) {
        tokens.push({ email: email(group[i]), accessToken: b.data.accessToken, refreshToken: b.data.refreshToken });
      } else {
        errors.push({ step: 'login', email: email(group[i]), status: res.status, code: errCode(res) });
      }
    });
  });

  // 3. 관심 매물 — 계정마다 WISHLIST 건. 이미 있으면 409 WISHLIST_DUPLICATED(정상)
  if (WISHLIST > 0) {
    const reqs = [];
    tokens.forEach(function (t, ti) {
      for (let k = 0; k < WISHLIST; k++) {
        const id = candidateIds[(ti * WISHLIST + k) % candidateIds.length];
        reqs.push({ t: t, r: post('/api/me/wishlist', { propertyId: id }, t.accessToken, 'wishlist_seed') });
      }
    });
    chunks(reqs, CONCURRENCY * 2).forEach(function (group) {
      const responses = http.batch(group.map(function (g) { return g.r; }));
      responses.forEach(function (res, i) {
        if (res.status !== 201 && !(res.status === 409 && errCode(res) === 'WISHLIST_DUPLICATED')) {
          errors.push({ step: 'wishlist', email: group[i].t.email, status: res.status, code: errCode(res) });
        }
      });
    });
  }

  console.log('가입 ' + created + ' · 기존 ' + existed + ' · 토큰 ' + tokens.length + '/' + COUNT + ' · 오류 ' + errors.length);
  return {
    generatedAt: new Date().toISOString(),
    baseUrl: BASE_URL,
    start: START,
    count: COUNT,
    created: created,
    existed: existed,
    tokens: tokens,
    errors: errors,
  };
}

export default function () {
  // 할 일은 setup 이 끝냈다
}

export function handleSummary(data) {
  const sd = data.setup_data;
  const out = {};
  if (!sd || !sd.tokens) {
    out.stdout = '\n토큰을 만들지 못했다 — setup 이 실패했다. 위 로그를 본다.\n';
    return out;
  }
  out[OUT] = JSON.stringify(sd, null, 2);
  let msg = '\n토큰 ' + sd.tokens.length + '/' + sd.count + ' 개 → ' + OUT +
    ' (가입 ' + sd.created + ' · 기존 ' + sd.existed + ')\n';
  if (sd.errors.length) {
    msg += '오류 ' + sd.errors.length + '건 — 첫 5건:\n' +
      sd.errors.slice(0, 5).map(function (e) { return '  ' + JSON.stringify(e); }).join('\n') + '\n';
  }
  out.stdout = msg;
  return out;
}
