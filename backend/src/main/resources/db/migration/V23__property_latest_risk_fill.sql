-- 매물 최신 판정 비정규화(PROP-02 · #403) — 2/4 채우기. 열은 V22, 인덱스는 V24 · V25.
--
-- 최신 판정 행(부분 유일 인덱스 uq_risk_analysis_latest (property_id) INCLUDE (risk_id, risk_grade, lease_ratio, registry_id)
-- WHERE is_latest, V19)에서 한 문장으로 옮긴다. 값이 이미 같은 행은 건너뛴다(IS DISTINCT FROM) — 같은 문장을 배포 뒤에 다시 돌려도
-- 어긋난 행만 고친다. 다시 돌릴 때: 배포 중 옛 앱 슬롯이 판정을 기록한 경우, 부하 데이터 스크립트처럼 판정 표에 SQL 로 직접 넣은 경우
-- (데이터베이스 설계서 매물 절의 확인 질의).
--
-- **운영 반영 전 EXPLAIN · 소요 확인.** 운영 매물 약 31만 행 전부를 고쳐 쓴다 — 행마다 새 판(tuple)이 생기고(죽은 판 31만 · WAL 이
-- 표 크기만큼), 커밋까지 고친 행의 행 잠금을 쥔다.
--   잠금 — 표 수준은 ROW EXCLUSIVE(읽기 · 다른 행 쓰기와 충돌하지 않는다). 매물 읽기는 primary · standby 모두 막히지 않는다.
--   막히는 것 — 같은 매물 행을 쓰는 앱 쓰기(갱신 배치 · 판정 기록의 매물 갱신 · 재분석 대기 표시)가 이 파일이 커밋할 때까지 기다린다.
--     앱 커넥션은 statement_timeout 30s(#405)라, 채우기가 30초를 넘으면 그동안 기다린 앱 쓰기는 취소되어 503 이 난다.
-- 채운 뒤 VACUUM (ANALYZE) property 가 필요하다 — 가시성 맵이 지워져 V24 커버링 인덱스의 인덱스 전용 스캔이 힙을 다시 읽는다. VACUUM 은
-- 트랜잭션 안에서 돌 수 없고, 31만 행 정리라 기동을 붙잡지 않도록 마이그레이션에 넣지 않는다 — 배포 뒤 운영자가 돌린다.
-- 인덱스(V24)보다 먼저 채운다 — 새 인덱스가 채우기의 행마다 갱신되지 않는다.
--
-- lock_timeout 5s — 행 잠금 대기에도 걸린다. 앱의 매물 쓰기는 짧아 5초를 넘게 행을 쥐지 않는다. 넘으면 비정상이라 실패시켜 배포를
-- 멈춘다 — 이 파일만 되돌려지고(V22 는 커밋되어 있다) 다시 기동하면 V23 부터 돈다.

SET LOCAL lock_timeout = '5s';

UPDATE property p
   SET risk_grade = ra.risk_grade,
       lease_ratio = ra.lease_ratio
  FROM risk_analysis ra
 WHERE ra.property_id = p.property_id
   AND ra.is_latest
   AND (p.risk_grade, p.lease_ratio) IS DISTINCT FROM (ra.risk_grade, ra.lease_ratio);
