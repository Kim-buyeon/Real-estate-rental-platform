-- 부하 시험 데이터 반영 준비 (#376). 운영 DB 에 loadtest 스키마와 적재용 표를 만든다.
-- 적재용 표는 UNLOGGED — WAL · 복제를 타지 않는다. 반영이 끝나면 00_drop.sql 로 지운다.
-- 실행: psql -v ON_ERROR_STOP=1 -f 00_stage.sql
CREATE SCHEMA IF NOT EXISTS loadtest;

-- 생성기 출력(gen_properties.py COLUMNS)과 같은 순서
CREATE UNLOGGED TABLE IF NOT EXISTS loadtest.stage_property (
    kind           TEXT    NOT NULL,          -- REAL · FAKE
    seq            INTEGER NOT NULL,          -- FAKE 의 무작위 순번(1부터). REAL 은 0
    address        VARCHAR(200) NOT NULL,
    district       VARCHAR(30)  NOT NULL,
    landlord_name  VARCHAR(50)  NOT NULL,
    contract_type  TEXT    NOT NULL,
    property_type  TEXT    NOT NULL,
    deposit        BIGINT  NOT NULL,
    monthly_rent   BIGINT  NOT NULL,
    market_price   BIGINT  NOT NULL,
    price_type     VARCHAR(20) NOT NULL,
    price_date     DATE    NOT NULL,
    area_sqm       NUMERIC(7, 2) NOT NULL,
    floor          INTEGER,
    built_year     INTEGER,
    latitude       NUMERIC(10, 7),
    longitude      NUMERIC(10, 7),
    sigungu_code   VARCHAR(5),
    bjdong_code    VARCHAR(5),
    bun            VARCHAR(4),
    ji             VARCHAR(4),
    registry_owner VARCHAR(50) NOT NULL,
    property_id    BIGINT                     -- 반영 때 채운다
);

-- 반영된 매물의 출처 — 실 · 가짜 구분과 판정 묶음 진행. 운영 화면과 무관하고 정리 · 검증에만 쓴다
CREATE TABLE IF NOT EXISTS loadtest.property_origin (
    property_id    BIGINT PRIMARY KEY,
    kind           TEXT    NOT NULL,
    district       VARCHAR(30) NOT NULL,
    registry_owner VARCHAR(50) NOT NULL,
    template_id    BIGINT,
    bundled        BOOLEAN NOT NULL DEFAULT FALSE,
    loaded_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNLOGGED TABLE IF NOT EXISTS loadtest.stage_users (
    user_id BIGINT, name VARCHAR(50), email VARCHAR(100), phone VARCHAR(20), role VARCHAR(20), annual_income BIGINT,
    credit_score INTEGER, existing_loan BIGINT, existing_loan_annual_payment BIGINT, has_house BOOLEAN, own_fund BIGINT,
    created_at TIMESTAMP, deleted_at TIMESTAMP);
CREATE UNLOGGED TABLE IF NOT EXISTS loadtest.stage_user_auth (
    user_id BIGINT, auth_type VARCHAR(10), provider_id VARCHAR(100), password_hash VARCHAR(255),
    last_login_at TIMESTAMP, created_at TIMESTAMP);

-- 반영 전 상태 — 실데이터 경계. 이 값보다 큰 property_id · user_id 1억 이상이 이번에 넣은 것이다
CREATE TABLE IF NOT EXISTS loadtest.baseline AS
SELECT now() AS taken_at,
       (SELECT coalesce(max(property_id), 0) FROM property)        AS max_property_id,
       (SELECT count(*) FROM property)                            AS property_count,
       (SELECT coalesce(max(registry_id), 0) FROM building_registry) AS max_registry_id,
       (SELECT coalesce(max(ledger_id), 0) FROM building_ledger)  AS max_ledger_id,
       (SELECT coalesce(max(risk_id), 0) FROM risk_analysis)      AS max_risk_id,
       (SELECT coalesce(max(user_id), 0) FROM users)              AS max_user_id,
       pg_current_wal_lsn()                                       AS lsn;
