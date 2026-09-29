-- 지도 2단계 묶음 질의 — PropertyMapper.xml selectClusterCells 를 MyBatis 가 펼친 모양 그대로(필터 없음 프리셋 = 부하 혼합 60%,
-- 자치구 + 표시 영역). #{} 는 나온 순서대로 $1 … $21 이다. 타입은 pgJDBC 가 보내는 것 — Double → float8, Integer → int4,
-- String → varchar(stringtype 기본). 타입이 다르면 계획이 달라지므로(numeric 열 대 float8 파라미터) 바꾸지 않는다.
-- 노드에서 처음 돌릴 때 collect-rc.sh end 의 statements.csv 에 나온 같은 질의 전문과 한 번 대조한다.
--   $1 · $3 · $7 minLat   $2 · $4 · $8 cellLat   $5 · $6 maxCellIndex     (cellRow)
--   $9 · $11 · $15 minLng $10 · $12 · $16 cellLng $13 · $14 maxCellIndex (cellCol)
--   $17 district   $18 minLat  $19 maxLat  $20 minLng  $21 maxLng           (filter)
-- 값 — 강남구 대략 중심(chaos-harness/load/lib/mix.js)에 2단계 표시 영역 반폭(0.03 · 0.04)을 소수 4자리 밖으로 반올림,
-- 칸 크기 = 폭 ÷ 12(GRID_DIVISIONS), maxCellIndex = 11. 한 예일 뿐이다 — 회차는 영역을 무작위로 옮긴다.
PREPARE q(float8, float8, float8, float8, int4, int4, float8, float8,
          float8, float8, float8, float8, int4, int4, float8, float8,
          varchar, float8, float8, float8, float8) AS
        SELECT g.row_index,
               g.col_index,
               COUNT(*) AS count,
               ROUND(AVG(g.latitude), 7) AS latitude,
               ROUND(AVG(g.longitude), 7) AS longitude,
               SUM(CASE WHEN g.risk_grade = 'SAFE' THEN 1 ELSE 0 END) AS safe_count,
               SUM(CASE WHEN g.risk_grade = 'CAUTION' THEN 1 ELSE 0 END) AS caution_count,
               SUM(CASE WHEN g.risk_grade = 'DANGER' THEN 1 ELSE 0 END) AS danger_count,
               SUM(CASE WHEN g.risk_grade IS NULL THEN 1 ELSE 0 END) AS unanalyzed_count,
               MIN(g.property_id) AS representative_id
          FROM (SELECT p.property_id,
                       p.latitude,
                       p.longitude,
                       ra.risk_grade,
                       CASE WHEN FLOOR((p.latitude - $1) / $2) < 0 THEN 0
                     WHEN FLOOR((p.latitude - $3) / $4) > $5 THEN $6
                     ELSE CAST(FLOOR((p.latitude - $7) / $8) AS INTEGER) END AS row_index,
                       CASE WHEN FLOOR((p.longitude - $9) / $10) < 0 THEN 0
                     WHEN FLOOR((p.longitude - $11) / $12) > $13 THEN $14
                     ELSE CAST(FLOOR((p.longitude - $15) / $16) AS INTEGER) END AS col_index
        FROM property p
        JOIN property_code ct ON ct.code_id = p.contract_type_code_id
        JOIN property_code pt ON pt.code_id = p.property_type_code_id
        LEFT JOIN risk_analysis ra ON ra.property_id = p.property_id AND ra.is_latest = TRUE
        WHERE p.district = $17
                AND p.latitude BETWEEN $18 AND $19
                AND p.longitude BETWEEN $20 AND $21) g
         GROUP BY g.row_index, g.col_index
         ORDER BY g.row_index, g.col_index;
-- EXEC: EXECUTE q(37.4872, 0.004999999999999598, 37.4872, 0.004999999999999598, 11, 11, 37.4872, 0.004999999999999598, 127.0073, 0.006666666666666525, 127.0073, 0.006666666666666525, 11, 11, 127.0073, 0.006666666666666525, '강남구', 37.4872, 37.5472, 127.0073, 127.0873);
