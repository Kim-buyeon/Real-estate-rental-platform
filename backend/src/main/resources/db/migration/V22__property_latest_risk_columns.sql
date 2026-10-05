-- 매물에 최신 판정의 등급 · 전세가율을 비정규화한다(PROP-02 · #403) — 1/4 열 추가.
-- 질의 쪽 변경(매물 매퍼가 판정 표 조인 대신 이 두 열을 읽는다)과 판정 기록의 갱신
-- (RiskAnalysisCommandService.record → PropertyRepository#applyLatestJudgement)과 짝이다.
--
-- 근거 — 2026-10-04 부하 시험(#390) E01 실제 값 EXPLAIN. 지도 · 집계 질의의 버퍼 대부분이 매물 행마다 판정 표의 최신 행을
-- 찾는 조인이었다.
--   지도 묶음(강남 2단계) 버퍼 79,081 중 판정 조인 71,488(행 23,829 × 3). 매물 쪽은 7,593. 147.9ms.
--   반경 마커(1 km) 버퍼 11,853 중 판정 조인 5,527. 15.7ms.
--   자치구 집계 판정 표 전체와 해시 조인 · 임시 파일 2,286 페이지. 464.6ms.
--
-- 두 열 — 판정 표의 같은 이름 열과 같은 형(V1). NULL = 최신 판정 없음(미분석). risk_analysis.lease_ratio 가 NOT NULL 이라 판정이
-- 있으면 두 열이 함께 차 있다.
--
-- 파일을 넷으로 나눈 이유 — 한 트랜잭션이면 이 ALTER TABLE 의 ACCESS EXCLUSIVE 잠금(매물 읽기까지 막는다)을 31만 행 채우기 · 인덱스
-- 생성이 끝날 때까지 쥔다. standby 도 이 잠금을 재생하는 동안 매물 읽기가 막힌다. Flyway 는 group 을 켜지 않으면(spring.flyway.group
-- 기본 false — application.yml 에 없다) 마이그레이션 파일마다 따로 트랜잭션을 열고 커밋한다(flyway-core 12.4.0 DbMigrate —
-- group 이 꺼져 있으면 대기 중 마이그레이션을 하나씩 적용한다). 그래서 잠금이 파일마다 풀린다.
--   V22 열 추가 — ACCESS EXCLUSIVE 를 잠깐. 기본값 없는 NULL 열 추가라 표를 다시 쓰지 않는다(PostgreSQL 11 이상) — 카탈로그만 바뀐다.
--   V23 채우기 — 행 잠금만. 읽기는 막지 않는다.
--   V24 인덱스 둘 — CREATE INDEX CONCURRENTLY, 트랜잭션 밖. 읽기 · 쓰기 모두 막지 않는다.
--   V25 옛 인덱스 삭제 · 새 인덱스 이름 바꾸기 — 짧은 강한 잠금.
--
-- 중간에 실패하면 — 트랜잭션 파일(V22 · V23 · V25)은 그 파일만 되돌려지고 이력에 남지 않는다. 앞 파일은 이미 커밋되어 이력에 있으므로
-- 다시 기동하면 Flyway 가 적용된 것을 건너뛰고 실패한 파일부터 다시 돈다. V24(트랜잭션 밖)는 다르다 — V24 주석.
--
-- lock_timeout 5s — 운영 기본값은 0(무한 대기)이다(V19 와 같은 이유). ALTER TABLE 이 잡을 ACCESS EXCLUSIVE 를 기다리는 동안 뒤에 오는
-- 매물 읽기도 줄을 서므로, 오래 기다리지 않고 실패시켜 배포를 멈춘다. SET LOCAL 은 이 파일의 트랜잭션 끝에서 풀린다.
-- Flyway 커넥션은 statement_timeout 0 이다(application.yml spring.flyway.init-sqls, #405).

SET LOCAL lock_timeout = '5s';

ALTER TABLE property
    ADD COLUMN risk_grade VARCHAR(10) NULL,
    ADD COLUMN lease_ratio NUMERIC(5, 2) NULL;
