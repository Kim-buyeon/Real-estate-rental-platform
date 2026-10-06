-- 실매물 상한 고르기 (INF-06 · #390, 매물 100만) — copy-property 로 실매물 풀 전체(≈233만)를 stage_property 에 넣은 뒤 한 번 돌린다.
--
-- 목표 총수 target_total(기본 1,000,000) − 기준선 매물 수(loadtest.baseline.property_count) 만큼을 실매물 풀에서 고른다. 가짜 0.
--   ① 기준선과 자연키가 같은 실매물은 뺀다(10_property 와 같은 자연키 — 넣을 때 건너뛸 행을 미리 빼야 총수가 정확하다)
--   ② 구별 몫 = 남은 풀에서 그 구가 차지하는 비율 × 넣을 수. 최대 잉여법(Hamilton) — 내림 합의 모자란 만큼을 나머지가 큰
--      구부터 1씩. 구 이름으로 동률을 끊는다 — 결정적
--   ③ 구 안에서는 md5(자연키 || salt) 순서로 몫만큼 — 고정 씨앗(salt, 기본 'inf06-1m'). 같은 풀 · 같은 salt 면 늘 같은 행
--   ④ 고른 행만 남긴 표로 stage_property 를 바꾼다(UNLOGGED). seq = 구 안 순번(1 ~ 몫)
-- 이미 매물 반영을 시작했으면(property_id 가 찬 행이 있으면) 멈춘다 — 고르기가 바뀌면 이어 돌리기의 「이미 넣은 것은 건너뛴다」가 깨진다.
-- 실행: psql -v ON_ERROR_STOP=1 [-v target_total=1000000] [-v salt=inf06-1m] -f 05_plan.sql
\set ON_ERROR_STOP 1
\if :{?target_total}
\else
  \set target_total 1000000
\endif
\if :{?salt}
\else
  \set salt inf06-1m
\endif
SET work_mem = '64MB';   -- 기준선 31만 자연키 해시 반조인이 디스크로 덜 넘치게

-- 멈춤은 \if 로 한다 — CASE 안의 상수 1/0 은 플래너가 미리 계산해 조건과 무관하게 터진다(PostgreSQL 문서 「CASE」 주의)
SELECT EXISTS (SELECT 1 FROM loadtest.stage_property WHERE property_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM loadtest.property_origin) AS already_loading \gset
\if :already_loading
  \warn '매물 반영을 이미 시작했다 — 고르기를 다시 하지 않는다(반영을 시작한 뒤 고르기가 바뀌면 이어 돌리기가 깨진다)'
  SELECT plan_must_run_before_any_load;
\endif

BEGIN;
-- 고를 후보는 행 위치 · 구 · 추첨값만 담는다(풀 전체를 임시 표로 베끼면 ≈ 800 MB 를 쓴다). stage_property 는 이 트랜잭션에서
-- 바뀌지 않으므로 행 위치(ctid)로 되짚는다
CREATE TEMP TABLE cand ON COMMIT DROP AS
SELECT s.ctid AS row_ref, s.district, md5(concat_ws('|', s.address, s.area_sqm, s.floor, s.deposit, s.monthly_rent, :'salt')) AS draw
  FROM loadtest.stage_property s
 WHERE s.kind = 'REAL'
   AND NOT EXISTS (SELECT 1 FROM property p
                    WHERE p.property_id <= (SELECT max_property_id FROM loadtest.baseline)
                      AND p.address = s.address AND p.area_sqm = s.area_sqm
                      AND p.floor IS NOT DISTINCT FROM s.floor
                      AND p.deposit = s.deposit AND p.monthly_rent = s.monthly_rent);

DROP TABLE IF EXISTS loadtest.real_quota;
CREATE TABLE loadtest.real_quota AS
WITH need AS (
    SELECT :target_total::bigint - (SELECT property_count FROM loadtest.baseline) AS n_new
), pool AS (
    SELECT district, count(*)::bigint AS pool FROM cand GROUP BY district
), tot AS (
    SELECT sum(pool)::bigint AS total FROM pool
), base AS (
    SELECT p.district, p.pool, (need.n_new * p.pool) / tot.total AS q0, (need.n_new * p.pool) % tot.total AS rem,
           need.n_new
      FROM pool p CROSS JOIN need CROSS JOIN tot
), ranked AS (
    SELECT b.*, row_number() OVER (ORDER BY rem DESC, district) AS rk,
           n_new - sum(q0) OVER () AS short
      FROM base b
)
SELECT district, pool, (q0 + CASE WHEN rk <= short THEN 1 ELSE 0 END)::int AS quota
  FROM ranked;

-- 풀이 넣을 수보다 작으면 멈춘다(오류 문장 → 트랜잭션째 되돌린다 — 몫 표 · 고르기 표가 남지 않는다)
SELECT (SELECT sum(pool) FROM loadtest.real_quota)
       < :target_total::bigint - (SELECT property_count FROM loadtest.baseline) AS pool_too_small \gset
\if :pool_too_small
  \warn '실매물 풀(기준선과 겹치는 것 뺀)이 넣을 수보다 작다'
  SELECT pool_too_small_stop;
\endif

CREATE UNLOGGED TABLE loadtest.stage_pick AS
SELECT s.kind, c.rn::int AS seq, s.address, s.district, s.landlord_name, s.contract_type, s.property_type, s.deposit,
       s.monthly_rent, s.market_price, s.price_type, s.price_date, s.area_sqm, s.floor, s.built_year, s.latitude,
       s.longitude, s.sigungu_code, s.bjdong_code, s.bun, s.ji, s.registry_owner, NULL::bigint AS property_id
  FROM (SELECT cand.*, row_number() OVER (PARTITION BY district ORDER BY draw) AS rn FROM cand) c
  JOIN loadtest.real_quota q ON q.district = c.district AND c.rn <= q.quota
  JOIN loadtest.stage_property s ON s.ctid = c.row_ref
 ORDER BY s.district, c.rn;

DROP TABLE loadtest.stage_property;
ALTER TABLE loadtest.stage_pick RENAME TO stage_property;
CREATE INDEX stage_property_district ON loadtest.stage_property (district, kind, seq);
COMMIT;
ANALYZE loadtest.stage_property;

SELECT q.district, q.pool, q.quota, round(100.0 * q.pool / sum(q.pool) OVER (), 2) AS pool_pct,
       round(100.0 * q.quota / sum(q.quota) OVER (), 2) AS quota_pct
  FROM loadtest.real_quota q ORDER BY q.quota DESC;
SELECT sum(quota) AS new_total, (SELECT property_count FROM loadtest.baseline) + sum(quota) AS property_total_after,
       (SELECT count(*) FROM loadtest.stage_property) AS staged
  FROM loadtest.real_quota;
