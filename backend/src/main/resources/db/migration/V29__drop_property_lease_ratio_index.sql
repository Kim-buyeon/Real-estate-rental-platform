-- 옛 전세가율순 인덱스 삭제(PROP-01 · #453) — ix_property_lease_ratio(V24). DROP INDEX CONCURRENTLY IF EXISTS, 트랜잭션 밖.
--
-- V28 의 ix_property_lease_ratio_filter 가 키 (lease_ratio, property_id) · 부분 조건(lease_ratio IS NOT NULL)이 같고 INCLUDE 가
-- 이 인덱스의 INCLUDE (risk_grade) 의 위집합이라, 이 인덱스로만 되는 계획이 없다. 남겨 두면 매물 쓰기마다 유지 비용(52 MB)만 든다.
-- V28 뒤에 둔다 — 새 인덱스가 생기기 전에 지우면 그 사이 자치구 없는 전세가율순 목록이 정렬 전체 읽기로 떨어진다.
--
-- 트랜잭션 밖에서 도는 근거 — DROP INDEX CONCURRENTLY 도 flyway-database-postgresql 12.4.0 PostgreSQLParser 의
-- ^(CREATE|DROP)( UNIQUE)? INDEX CONCURRENTLY 에 맞아 트랜잭션 없이 실행된다(V24 주석). 파일에 이 문장만 둔다.
--
-- 잠금 — SHARE UPDATE EXCLUSIVE. 이 인덱스를 쓰는 진행 중인 질의 · 트랜잭션이 끝나기를 기다린 뒤 지우고, 매물 읽기 · 쓰기를 막지 않는다
-- (일반 DROP INDEX 는 ACCESS EXCLUSIVE 라 표 전체를 막는다).
--
-- IF EXISTS 인 이유 — 측정 · 수작업으로 이미 지운 환경에서 실패하지 않게 한다. 지울 대상이라 조용히 건너뛰어도 잃는 것이 없다.
--
-- 중간에 실패하면 — 트랜잭션 밖이라 INVALID 상태의 인덱스가 남을 수 있고, 다음 기동은 「failed migration」으로 멈춘다(V24 주석).
-- 복구 — DROP INDEX IF EXISTS ix_property_lease_ratio 로 남은 것을 지우고, flyway_schema_history 의 V29 실패 행을 지운 뒤 다시 기동한다.

DROP INDEX CONCURRENTLY IF EXISTS ix_property_lease_ratio;
