-- 매물 좌표 복합 인덱스 — 아키텍처 설계서(영속성 구조) 1.3
-- 지도 마커 조회는 공간 확장 없이 (latitude, longitude) B-tree + BETWEEN 으로 1차 필터한다.
CREATE INDEX idx_property_lat_lng ON property (latitude, longitude);
