-- 매물마다 최신 분석(is_latest = TRUE)은 하나다. 두 인스턴스가 같은 매물을 동시에 처음 분석하면 둘 다 최신 행을
-- 넣을 수 있어 DB 가 막는다. 목록 · 지도 · 집계 매퍼가 is_latest 로 조인하므로 조인 인덱스도 겸한다.
CREATE UNIQUE INDEX uq_risk_analysis_latest ON risk_analysis (property_id) WHERE is_latest;
