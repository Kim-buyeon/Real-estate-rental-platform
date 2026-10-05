-- 매물에 최신 판정의 등급 · 전세가율을 비정규화한다(PROP-02 · #403). 질의 쪽 변경(매물 매퍼가 판정 표 조인 대신 이 두 열을
-- 읽는다)과 판정 기록의 갱신(RiskAnalysisCommandService.record → PropertyRepository#applyLatestJudgement)과 짝이다.
--
-- 근거 — 2026-10-04 부하 시험(#390) E01 실제 값 EXPLAIN. 지도 · 집계 질의의 버퍼 대부분이 매물 행마다 판정 표의 최신 행을
-- 찾는 조인이었다.
--   지도 묶음(강남 2단계) 버퍼 79,081 중 판정 조인 71,488(행 23,829 × 3). 매물 쪽은 7,593. 147.9ms.
--   반경 마커(1 km) 버퍼 11,853 중 판정 조인 5,527. 15.7ms.
--   자치구 집계 판정 표 전체와 해시 조인 · 임시 파일 2,286 페이지. 464.6ms.
-- 판정 표 조인이 사라지면 지도 · 집계가 매물 표만 읽는다.
--
-- 1) 두 열 — 판정 표의 같은 이름 열과 같은 형(V1). NULL = 최신 판정 없음(미분석). risk_analysis.lease_ratio 가 NOT NULL 이라
--    판정이 있으면 두 열이 함께 차 있다. 기본값 없는 NULL 열 추가라 표를 다시 쓰지 않는다(PostgreSQL 11 이상).
--
-- 2) 채우기 — 최신 판정 행(부분 유일 인덱스 uq_risk_analysis_latest (property_id) INCLUDE (risk_id, risk_grade, lease_ratio,
--    registry_id) WHERE is_latest, V19)에서 한 문장으로. 값이 이미 같은 행은 건너뛴다(IS DISTINCT FROM) — 같은 문장을 배포 뒤에
--    다시 돌려도 어긋난 행만 고친다. 다시 돌릴 때: 배포 중 옛 앱 슬롯이 판정을 기록한 경우, 부하 데이터 스크립트처럼 판정 표에
--    SQL 로 직접 넣은 경우.
--    **운영 반영 전 EXPLAIN · 소요 확인.** 운영 매물 약 31만 행 전부를 고쳐 쓴다 — 행마다 새 판(tuple)이 생기고(죽은 판 31만 ·
--    WAL 이 표 크기만큼), 커밋까지 고친 행의 행 잠금을 쥐어 그동안 같은 매물의 쓰기(갱신 배치 · 판정 기록 · 재분석 대기 표시)가
--    기다린다. 읽기는 막지 않는다. 채운 뒤 VACUUM (ANALYZE) property 가 필요하다 — 가시성 맵이 지워져 아래 커버링 인덱스의
--    인덱스 전용 스캔이 힙을 다시 읽는다. VACUUM 은 트랜잭션 안에서 돌 수 없어 여기에 넣지 않는다.
--    인덱스를 만들기 전에 채운다 — 새 인덱스가 채우기의 행마다 갱신되지 않는다.
--
-- 3) ix_property_lease_ratio — V20 의 ix_risk_analysis_latest_lease_ratio 가 하던 역할(자치구 · 매물 조건 필터가 없는 전세가율순
--    목록을 인덱스 순서로 읽고 LIMIT 에서 멈춤)을 매물 쪽으로 옮긴다. 정의는 V20 과 같은 모양 — (전세가율, 매물 ID) 순, 등급은
--    INCLUDE, 부분 조건은 전세가율 있음. 질의 쪽 범위 조건(미분석 대체값 경계)이 필요한 것도 V20 과 같다(매퍼 주석).
--
-- 4) idx_property_district_lat_lng 를 커버링으로 교체 — V19 가 판정 표 인덱스에 INCLUDE 로 담아 지도 2단계의 판정 조인을
--    인덱스 전용 스캔으로 만든 역할을 매물 쪽으로 옮긴다. 지도 묶음이 읽는 열(매물 ID · 등급)을 INCLUDE 로 담아 필터 없는 지도
--    묶음이 매물 힙(E01 강남 7,593 버퍼)도 읽지 않게 한다. 자치구 집계(자치구 · 등급)도 이 인덱스로 인덱스 전용 스캔을 할 수
--    있어 집계용 인덱스를 따로 두지 않는다 — 실행 계획은 운영 EXPLAIN 으로 확인한다(미검증). 이름 · 키 열은 V15 와 같다 — 마커 ·
--    묶음 질의의 좌표 조건이 그대로 쓴다. 새 것을 먼저 만들고 옛 것을 맨 끝에 지운다(V19 와 같은 순서).
--    대가 — 등급이 인덱스 열이 되어 등급이 바뀌는 판정 기록의 매물 UPDATE 가 HOT 이 되지 않는다. 등급 변경은 드물고, 매물
--    갱신은 이미 재분석 대기 부분 인덱스(V18) 때문에 HOT 이 아니다(V19 주석).
--
-- 넣지 않은 것:
-- - ix_risk_analysis_latest_lease_ratio(V20) · uq_risk_analysis_latest(V19) 삭제 — uq_risk_analysis_latest 는 유일성 · 위험도
--   조회의 최신 행 찾기 · 관심 목록 · 마커의 등기 참조 · 상세의 보증 가입 여부가 계속 쓴다. V20 인덱스는 이 변경 뒤 코드에서 쓰는
--   질의가 없지만, 운영 pg_stat_user_indexes 의 idx_scan 이 늘지 않는 것을 배포 뒤 확인하고 지운다.
-- - 지도 2단계 판정 조인용 집계 인덱스(district, risk_grade) — 4) 의 커버링이 같은 열을 담는다.
--
-- CONCURRENTLY 를 쓰지 않는다 — V19 와 같다. Flyway 는 PostgreSQL 마이그레이션을 한 트랜잭션으로 감싸고, CONCURRENTLY 는 트랜잭션
-- 안에서 돌 수 없다. 인덱스 생성 동안 매물 표의 쓰기가 멈춘다(V19 의 매물 인덱스 생성 916ms 참고). 옛 인덱스 삭제는 매물 표의
-- 읽기까지 막는 잠금이라 맨 끝에 둬 쥐는 시간을 줄인다. lock_timeout 5s 는 V19 와 같은 이유다 — 운영 기본값은 0(무한 대기)이다.

SET LOCAL lock_timeout = '5s';

ALTER TABLE property
    ADD COLUMN risk_grade VARCHAR(10) NULL,
    ADD COLUMN lease_ratio NUMERIC(5, 2) NULL;

UPDATE property p
   SET risk_grade = ra.risk_grade,
       lease_ratio = ra.lease_ratio
  FROM risk_analysis ra
 WHERE ra.property_id = p.property_id
   AND ra.is_latest
   AND (p.risk_grade, p.lease_ratio) IS DISTINCT FROM (ra.risk_grade, ra.lease_ratio);

CREATE INDEX ix_property_lease_ratio ON property (lease_ratio, property_id)
    INCLUDE (risk_grade) WHERE lease_ratio IS NOT NULL;

CREATE INDEX idx_property_district_lat_lng_cov ON property (district, latitude, longitude)
    INCLUDE (property_id, risk_grade);

-- 강한 잠금(매물 표 읽기까지 막는다)을 짧게 쥐도록 맨 끝.
DROP INDEX idx_property_district_lat_lng;

ALTER INDEX idx_property_district_lat_lng_cov RENAME TO idx_property_district_lat_lng;
