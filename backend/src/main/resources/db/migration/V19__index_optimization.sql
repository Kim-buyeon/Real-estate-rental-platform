-- 성능 원인 조사(#291 · #293) 뒤 조회 인덱스 정리(PROP-01 · #321). 질의 쪽 변경(매물 매퍼의 좌표 형 변환 · 선순위 조건 제거)과 짝이다.
--
-- 근거 — 운영 DB-01(2026-09-30, 매물 298,443건)에서 트랜잭션 안에 만들고 되돌려 잰 값. 원자료는 PR 본문.
--
-- 0) lock_timeout 5s — 운영 기본값은 0(무한 대기)이다. 앱 질의 최장은 목록 596ms 이고, 잠금 대기가 이를 크게 넘으면
--    비정상이므로 기다리지 않고 실패시켜 배포를 멈춘다. SET LOCAL 은 트랜잭션 끝에서 풀린다 — Flyway 는 PostgreSQL 의 이
--    마이그레이션을 한 트랜잭션으로 감싼다(트랜잭션 밖에서만 도는 문장 — CONCURRENTLY · VACUUM 등 — 이 없다).
-- 1) ANALYZE — 시드만 들어 있는 코드 · 기준 표 아홉은 자동 분석 문턱(50행 + 10%)에 닿지 않아 한 번도 분석되지 않았다
--    (운영 pg_stat_user_tables 의 마지막 분석 시각이 비어 있다). 통계가 없으면 플래너가 페이지 수로 행 수를 짐작한다. 15ms.
-- 2) uq_risk_analysis_latest 를 커버링으로 교체 — 지도가 읽는 판정 열(risk_id · risk_grade · lease_ratio · registry_id)을
--    INCLUDE 로 담아 지도 2단계의 판정 조인이 인덱스 전용 스캔이 된다. 강남 2단계 181.8 → 134.3ms,
--    배포 뒤 CLUSTER · VACUUM(3단계 운영 작업, 가시성 맵을 채움)까지 더하면 185.8 → 100.5ms. 생성 312ms.
--    이름을 유지한다 — RiskAnalysisCommandService 의 동시 분석 경합 처리 주석과 테스트가 이 이름을 가리킨다.
--    유일 · 부분 조건(WHERE is_latest)은 V9 와 같다. 새 인덱스를 먼저 만들고 옛 것을 맨 끝에 지운다 — 사이에 유일성이 빠지지 않는다.
-- 3) mortgage_history (registry_id) WHERE is_active — 마커의 선순위 채무 EXISTS 가 인덱스 전용 스캔이 된다. 생성 187ms.
--    senior_debt_yn 은 조건에 넣지 않는다 — 계약 전 조회자에게 등기상 유효 항목은 전부 선순위라 수집 경로가 늘 참으로
--    저장한다(MortgageHistory.record 주석, 운영 26만 8,441건 전부 참). 말소(is_active = FALSE) 13%만 빠진다.
--    V15 의 idx_mortgage_history_registry 는 위험도 분석의 등기 이력 조회(말소 포함)가 쓰므로 둔다.
-- 4) property (registered_at DESC, property_id DESC) — 자치구 없는 목록의 기본 정렬. 전체 병렬 스캔 → 정렬을 하던 것이
--    이 순서로 읽고 LIMIT 에서 멈춘다. 596 → 0.4ms. 생성 916ms. 자치구가 있으면 V15 의 (district, ...) 를 쓴다.
-- 5) wishlist (user_id, wish_id DESC) — 관심 목록이 기본 키 역순으로 관심 표 전체를 훑으며 user_id 로 걸렀다(누적
--    5억 3천만 행 읽음). 생성 4ms.
-- 6) risk_analysis (ledger_id) — Mock 대장 교체(MockLedgerReplaceExecutor)의 대장 떼기 UPDATE 와 대장 삭제의 FK 검사가
--    판정 표 36.5만 행을 매번 전체 스캔했다. V1 의 FK 는 인덱스를 만들지 않는다. 생성 178ms.
-- 7) wishlist_notification (wish_id) — V13 의 ON DELETE SET NULL 로 관심 해제마다 관심 알림 표에서 그 관심을 찾는다. 생성 2ms.
--
-- 넣지 않은 것:
-- - risk_analysis (registry_id) — 등기 행을 지우는 경로도, 등기 id 로 판정을 찾는 조회도 없다.
-- - 확장 통계(district · latitude · longitude) — 지도 영역 추정 행 수는 3,931 → 6,487 로 실제에 가까워졌지만 계획이 판정
--   전체 스캔으로 바뀌어 지도 2단계가 186ms 로 느려졌다(측정).
-- - 채움률(fillfactor) — 갱신이 인덱스 조건 열(is_reanalysis_pending · is_latest)을 바꾸므로 HOT 갱신이 되지 않는다(실험 확인).
--
-- CONCURRENTLY 를 쓰지 않는다 — Flyway 가 마이그레이션을 트랜잭션으로 감싸고, 위 생성 시간은 합쳐 1.6초 남짓이다. 트랜잭션 안의
-- 잠금은 커밋까지 쥐므로 그동안 대상 표의 쓰기가 멈춘다. 옛 인덱스 삭제(4ms)는 판정 표의 읽기까지 막는 잠금이라 맨 끝에 둬
-- 쥐는 시간을 줄인다.

SET LOCAL lock_timeout = '5s';

ANALYZE property_code, guarantee_criteria, hug_criteria, hf_criteria, sgi_criteria, guarantee_premium_rate,
        insurance_product, loan_regulation, risk_criteria;

CREATE UNIQUE INDEX uq_risk_analysis_latest_cov ON risk_analysis (property_id)
    INCLUDE (risk_id, risk_grade, lease_ratio, registry_id) WHERE is_latest;

CREATE INDEX idx_mortgage_history_registry_active ON mortgage_history (registry_id) WHERE is_active;

CREATE INDEX idx_property_registered ON property (registered_at DESC, property_id DESC);

CREATE INDEX idx_wishlist_user_wish ON wishlist (user_id, wish_id DESC);

CREATE INDEX idx_risk_analysis_ledger ON risk_analysis (ledger_id);

CREATE INDEX idx_wishlist_notification_wish ON wishlist_notification (wish_id);

-- 강한 잠금(판정 표 읽기까지 막는다)을 짧게 쥐도록 맨 끝.
DROP INDEX uq_risk_analysis_latest;

ALTER INDEX uq_risk_analysis_latest_cov RENAME TO uq_risk_analysis_latest;
