-- 건축물대장 실연동(PROP-04) — 건축HUB 건축물대장정보 서비스의 요청 파라미터를 매물에 저장하고,
-- 그 서비스가 주지 않는 값을 대장 행이 비워 둘 수 있게 한다.
--
-- 1. property 의 대장 조회 키. 건축HUB 요청 파라미터 sigunguCd · bjdongCd · bun · ji 와 같은 값이다.
--      sigungu_code — 시군구 코드 5자리. 전월세 실거래 조회 요청의 지역 코드(LAWD_CD)
--      bjdong_code  — 법정동 코드 5자리. 도로명주소 검색 응답의 법정동 코드(admCd, 10자리)의 뒤 5자리
--      bun · ji     — 지번의 본번 · 부번을 앞에 0 을 채운 4자리. "200-16" → 0200 · 0016, "702" → 0702 · 0000
--    NULL 을 허용한다. 이 변경 전에 적재된 매물은 값이 없고 갱신 배치의 적재 단계가 채운다. 산 지번처럼 형식 밖의
--    지번이나 법정동 코드를 받지 못한 매물은 계속 비어 있다. 넷은 한 묶음이라 전부 있거나 전부 없다 — 일부만 있으면
--    대장을 조회할 수 없는 키가 채워진 것처럼 보인다.
ALTER TABLE property
    ADD COLUMN sigungu_code VARCHAR(5),
    ADD COLUMN bjdong_code  VARCHAR(5),
    ADD COLUMN bun          VARCHAR(4),
    ADD COLUMN ji           VARCHAR(4);

ALTER TABLE property
    ADD CONSTRAINT ck_property_ledger_key_all_or_none
        CHECK (num_nulls(sigungu_code, bjdong_code, bun, ji) IN (0, 4));

-- 2. building_ledger 에서 건축HUB 가 주지 않는 값.
--      owner_name    — 표제부에 소유자 항목이 없다
--      building_area — 표제부의 건축면적(archArea)이 0 으로 오는 건물이 있다(미기재). 0 을 면적으로 저장하지 않는다
--      violation_yn  — 위반건축물 여부는 건축HUB 9개 기능 어디에도 없다(2026-09-30 실호출 확인). NULL 은 「확인하지
--                      못함」이다. 기본값 FALSE 를 지운다 — 값을 빠뜨린 저장이 「위반 아님」으로 남으면 확인하지 않은 값이
--                      보증 판정의 통과 근거가 된다
ALTER TABLE building_ledger
    ALTER COLUMN owner_name DROP NOT NULL,
    ALTER COLUMN building_area DROP NOT NULL,
    ALTER COLUMN violation_yn DROP NOT NULL,
    ALTER COLUMN violation_yn DROP DEFAULT;

-- 3. risk_analysis.ledger_id — 뗄 대장이 없는 매물(조회 키 없음 · 필지에 맞는 표제부 없음)도 분석한다. 대장 행을 지어내지
--    않으므로 분석 행이 가리킬 대장이 없을 수 있다. NULL 은 「대장 없이 분석함」이다 — 위반건축물 · 대장 주소 · 면적 대조는
--    확인 불가로 두고 보증 불가 사유로 쓰지 않는다.
ALTER TABLE risk_analysis
    ALTER COLUMN ledger_id DROP NOT NULL;
