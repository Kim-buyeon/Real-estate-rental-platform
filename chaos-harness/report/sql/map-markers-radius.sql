-- 지도 3단계(반경) 질의 — PropertyMapper.xml selectMarkers 를 MyBatis 가 펼친 모양 그대로(필터 없음 프리셋, 자치구 + 반경의
-- 바운딩 박스). 서비스(PropertyQueryService.searchMarkersInRadius)가 GeoDistanceCalculator.boundingBox 로 박스를 만들어 넘기고,
-- 반경 밖 제거 · 거리순은 앱에서 한다 — DB 가 보는 것은 이 질의뿐이다.
-- #{} 순서 — $1 district  $2 minLat  $3 maxLat  $4 minLng  $5 maxLng. 타입은 pgJDBC 가 보내는 것(String → varchar, Double → float8).
-- 노드에서 처음 돌릴 때 collect-rc.sh end 의 statements.csv 에 나온 같은 질의 전문과 한 번 대조한다.
-- 값 — 강남구 대략 중심(chaos-harness/load/lib/mix.js) 반경 1.0 km(S3_RADIUS_KM)의 박스. 한 예일 뿐이다 — 회차는 본 마커 좌표를 중심으로 쓴다.
PREPARE q(varchar, float8, float8, float8, float8) AS
        SELECT p.property_id,
               p.latitude,
               p.longitude,
               p.deposit,
               ra.risk_grade,
               ct.code_value AS contract_type,
               COALESCE(p.monthly_rent, 0) AS monthly_rent,
               p.district,
               ra.lease_ratio AS debt_ratio,
               CASE WHEN ra.risk_id IS NULL THEN NULL
                    ELSE EXISTS (SELECT 1
                                   FROM mortgage_history mh
                                  WHERE mh.registry_id = ra.registry_id
                                    AND mh.senior_debt_yn = TRUE
                                    AND mh.is_active = TRUE)
               END AS has_senior_debt
        FROM property p
        JOIN property_code ct ON ct.code_id = p.contract_type_code_id
        JOIN property_code pt ON pt.code_id = p.property_type_code_id
        LEFT JOIN risk_analysis ra ON ra.property_id = p.property_id AND ra.is_latest = TRUE
        WHERE p.district = $1
                AND p.latitude BETWEEN $2 AND $3
                AND p.longitude BETWEEN $4 AND $5
        ORDER BY p.property_id;
-- EXEC: EXECUTE q('강남구', 37.50820679636276, 37.52619320363725, 127.03596170262563, 127.05863829737439);
