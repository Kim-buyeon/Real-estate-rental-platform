-- 시험 계정 상태 — 읽기 전용(INF-06 #390). 가입이 필요한지 · 몇 개가 비밀번호로 로그인할 수 있는지 본다.
--
-- 돌리는 법 — 측정 스크립트의 psql_node 로 standby(db02)에서 읽는다(primary 에 부하를 주지 않는다. 비밀번호를 다루지 않는다).
--   bash -c '. chaos-harness/measure/node/lib.sh; psql_node db02 < chaos-harness/jmeter/accounts-status.sql'
-- 출력은 「키,값」 줄이다(psql_node 가 -At -F, — 머리 없음 · 쉼표).
--
-- **EXPLAIN 먼저.** 운영 DB 에서 돌리기 전에 두 SELECT 앞에 EXPLAIN 만 붙여 한 번 돌려 계획을 본다 — 표마다 순차 스캔 한 번이고
-- 예상 행수가 표 크기와 맞는지. LIKE '<접두어>+%' 는 유일 인덱스 (auth_type, provider_id)(V1)를 쓰지 못한다(기본 콜레이션의
-- btree 는 LIKE 앞머리 검색을 하지 않는다) — 그래서 user_auth · users 를 **각각 한 번만** 읽는다. user_auth 는 작은 묶음으로
-- 먼저 줄인 뒤(GROUP BY) 키마다 더하고, users 는 FILTER 로 한 번에 센다.
-- 회원이 많으면(수십만 이상) 시간이 걸린다 — statement_timeout 이 끊는다.
--
-- 열은 V1__init_schema.sql 과 엔티티(User · UserAuth)로 확인했다 — users(email · deleted_at), user_auth(auth_type · provider_id ·
-- password_hash · last_login_at). 이메일 가입은 user_auth.provider_id 와 users.email 에 같은 이메일을 넣는다(User · UserAuth 의 signUpWithEmail).
-- 접두어 둘 — loadtest+ 는 9/26 부하 계정(비밀번호를 모른다 — chaos-harness/load/make-tokens.js), jmeter+ 는 이 시험의 계정(make-accounts.sh).
-- 해시 앞머리 $2a$10$ 이 앱의 BCrypt 강도 10(SecurityConfig)이다. 해시 자체는 내지 않는다.

BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '60s';

-- user_auth 한 번 — 전체 · 인증 수단별 · 해시 앞머리별 · 접두어별(이메일 가입만) · 해시 있음 · 로그인한 적 있음
WITH g AS (
    SELECT auth_type,
           COALESCE(left(password_hash, 7), 'null')        AS hp,
           provider_id LIKE 'loadtest+%@rental.test'      AS lt,
           provider_id LIKE 'jmeter+%@rental.test'        AS jm,
           last_login_at IS NOT NULL                       AS ll,
           count(*)                                        AS n
      FROM user_auth
     GROUP BY 1, 2, 3, 4, 5
)
SELECT t.k, sum(g.n)
  FROM g,
       LATERAL (VALUES ('user_auth_total'),
                       ('user_auth_by_type_' || g.auth_type),
                       ('hash_prefix_' || g.hp),
                       (CASE WHEN g.auth_type = 'EMAIL' AND g.lt THEN 'email_loadtest' END),
                       (CASE WHEN g.auth_type = 'EMAIL' AND g.lt AND g.hp <> 'null' THEN 'email_loadtest_with_hash' END),
                       (CASE WHEN g.auth_type = 'EMAIL' AND g.jm THEN 'email_jmeter' END),
                       (CASE WHEN g.auth_type = 'EMAIL' AND g.jm AND g.hp <> 'null' THEN 'email_jmeter_with_hash' END),
                       (CASE WHEN g.auth_type = 'EMAIL' AND g.jm AND g.ll THEN 'email_jmeter_logged_in' END)) t(k)
 WHERE t.k IS NOT NULL
 GROUP BY t.k
 ORDER BY t.k;

-- users 한 번 — 전체 · 탈퇴 · 시험 계정의 탈퇴(탈퇴한 회원으로는 로그인 시험이 성립하지 않는다)
SELECT t.k, t.v
  FROM (SELECT count(*)                                                                      AS total,
               count(*) FILTER (WHERE deleted_at IS NOT NULL)                                AS deleted,
               count(*) FILTER (WHERE email LIKE 'jmeter+%@rental.test')                     AS jmeter,
               count(*) FILTER (WHERE email LIKE 'jmeter+%@rental.test' AND deleted_at IS NOT NULL) AS jmeter_deleted
          FROM users) u,
       LATERAL (VALUES ('users_total', u.total), ('users_deleted', u.deleted),
                       ('users_jmeter', u.jmeter), ('users_jmeter_deleted', u.jmeter_deleted)) t(k, v);

ROLLBACK;
