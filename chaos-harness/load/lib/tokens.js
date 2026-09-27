// 토큰 풀 — 트래픽 정의서 3.4 「인증: 사전 발급 토큰 풀 100개」.
//
// 매 요청 로그인하면 bcrypt 해싱이 측정 대상을 오염시키므로, make-tokens.js 가 미리 로그인해 둔 토큰을
// 파일(tokens.json)로 받아 쓴다.
//
// ── 코드에서 확인한 제약 (UserCommandService.reissue · application.yml jwt) ──────────────────
// 1. 리프레시 토큰은 사용자당 「현재 토큰」 하나만 유효하다(회전). 같은 계정의 리프레시 토큰을 두 VU 가 들고
//    있다가 한쪽이 재발급하면 다른 쪽의 토큰은 그 순간 무효(401 AUTH_INVALID_CREDENTIAL)가 된다.
//    k6 는 VU 사이에 바뀌는 상태를 공유할 수단이 없으므로 **한 계정의 재발급은 한 VU(소유자)만 한다.**
// 2. 액세스 토큰은 회전돼도 무효화되지 않는다 — 만료(1800초)까지 쓸 수 있다. 그래서 소유자가 아닌 VU 도
//    같은 계정의 액세스 토큰으로 조회는 계속 할 수 있다. 다만 만료되면 스스로 갱신할 수 없다.
// 3. make-tokens.js 를 다시 돌리면(로그인) 그 계정의 리프레시 토큰이 새로 회전된다 — 이전 파일은 쓸 수 없다.
//    본 스크립트가 돌고 나면 파일의 리프레시 토큰도 이미 회전됐으므로 **실행마다 make-tokens.js 를 다시 돌린다.**
//
// 결과: 풀 크기 N 이 VU 수보다 작고 시험이 액세스 토큰 수명(30분)보다 길면, 소유자가 아닌 VU 는 30분 뒤부터
// 인증 요청이 401 이 된다. 그 건수는 lt_auth_expired_shared 로 따로 센다. 피하려면 N 을 maxVUs 이상으로 만든다.

import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import http from 'k6/http';
import encoding from 'k6/encoding';

/**
 * 토큰 파일을 SharedArray 로 올린다. init 컨텍스트에서만 부를 수 있다 — open() 이 init 전용이다.
 * open 은 진입 스크립트가 넘긴다(상대 경로의 기준이 진입 스크립트가 되도록).
 *
 * 파일 형식(make-tokens.js 출력): { generatedAt, baseUrl, tokens: [{ email, accessToken, refreshToken }] }
 * 배열만 있는 파일([{ accessToken, refreshToken }])도 받는다.
 */
export function loadTokenPool(name, path, openFn) {
  return new SharedArray(name, function () {
    const parsed = JSON.parse(openFn(path));
    const list = Array.isArray(parsed) ? parsed : parsed.tokens;
    if (!Array.isArray(list) || list.length === 0) {
      throw new Error(`토큰 파일이 비었거나 형식이 다르다: ${path}`);
    }
    list.forEach(function (t, i) {
      if (!t.accessToken || !t.refreshToken) {
        throw new Error(`토큰 파일 ${path} 의 ${i} 번째 항목에 accessToken · refreshToken 이 없다`);
      }
    });
    return list;
  });
}

/** JWT 의 exp(초). 서명은 검증하지 않는다 — 남은 수명을 보려는 것뿐이다. 읽지 못하면 0. */
export function jwtExpSec(jwt) {
  try {
    const payload = jwt.split('.')[1];
    return JSON.parse(encoding.b64decode(payload, 'rawurl', 's')).exp || 0;
  } catch (e) {
    return 0;
  }
}

/**
 * setup() 에서 부른다. 풀 크기와 액세스 토큰 남은 수명을 보고 경고를 찍는다(시험을 멈추지는 않는다).
 * 반환값은 setup 데이터에 넣어 요약에 남긴다.
 */
export function inspectPool(pool, maxVUs, plannedSec) {
  const nowSec = Math.floor(Date.now() / 1000);
  let minLeft = Infinity;
  for (let i = 0; i < pool.length; i++) {
    const left = jwtExpSec(pool[i].accessToken) - nowSec;
    if (left < minLeft) minLeft = left;
  }
  const report = { poolSize: pool.length, maxVUs: maxVUs, minAccessLeftSec: minLeft, plannedSec: plannedSec, warnings: [] };
  if (minLeft <= 0) {
    report.warnings.push('액세스 토큰이 이미 만료됐다 — make-tokens.js 를 방금 다시 돌렸는지 확인한다. 소유자 VU 만 재발급으로 회복한다.');
  }
  if (maxVUs > pool.length && plannedSec > minLeft) {
    report.warnings.push(
      `maxVUs(${maxVUs}) > 풀(${pool.length}) 이고 시험 길이(${plannedSec}s) > 액세스 토큰 남은 수명(${minLeft}s). ` +
        '소유자가 아닌 VU 는 만료 뒤 인증 요청이 401 이 된다(lt_auth_expired_shared). 풀을 maxVUs 이상으로 만들면 없어진다.');
  }
  report.warnings.forEach(function (w) { console.warn('[tokens] ' + w); });
  return report;
}

// ── VU 별 세션 ─────────────────────────────────────────────────────────────
// 모듈 전역 변수는 VU 마다 따로 있다(k6 는 VU 마다 JS 런타임을 따로 띄운다).
let session = null;

/**
 * 이 VU 의 세션. 슬롯 = (VU 번호 - 1) mod N. 소유자 = VU 번호 ≤ N (그 슬롯을 쓰는 첫 VU).
 * VU 번호(idInTest)는 시험 전체에서 유일하고 1부터 붙는다.
 */
export function getSession(pool) {
  if (session === null) {
    const id = exec.vu.idInTest;
    const slot = (id - 1) % pool.length;
    const t = pool[slot];
    session = {
      slot: slot,
      owner: id <= pool.length,
      accessToken: t.accessToken,
      refreshToken: t.refreshToken,
      accessExpSec: jwtExpSec(t.accessToken),
      broken: false, // 소유자인데 재발급이 거절됐다 — 체인이 끊겼다(다른 프로세스가 같은 계정을 썼다)
    };
  }
  return session;
}

export function authHeaders(s) {
  return { Authorization: 'Bearer ' + s.accessToken };
}

/**
 * POST /api/auth/reissue — 회원·인증 명세, AuthController.reissue. 본문 { refreshToken }, 응답 data 는
 * { accessToken, refreshToken, tokenType, expiresIn, isNewUser }. 소유자만 부른다.
 * 성공하면 세션을 갈아 끼우고 응답을 돌려준다. 실패하면 응답만 돌려준다.
 */
export function reissue(s, baseUrl, params) {
  const res = http.post(
    baseUrl + '/api/auth/reissue',
    JSON.stringify({ refreshToken: s.refreshToken }),
    Object.assign({}, params, { headers: { 'Content-Type': 'application/json' } }));
  if (res.status === 200) {
    try {
      const d = res.json('data');
      s.accessToken = d.accessToken;
      s.refreshToken = d.refreshToken;
      s.accessExpSec = jwtExpSec(d.accessToken);
    } catch (e) {
      // 검증은 호출한 쪽이 한다
    }
  } else if (res.status === 401) {
    s.broken = true;
  }
  return res;
}

/** 액세스 토큰이 marginSec 안에 만료되는가. */
export function expiresWithin(s, marginSec) {
  return s.accessExpSec - Math.floor(Date.now() / 1000) < marginSec;
}
