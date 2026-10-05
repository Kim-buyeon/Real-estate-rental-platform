-- 매물 최신 판정 비정규화(PROP-02 · #403) — 4/4 지도 인덱스 교체 마무리. V24 의 커버링 인덱스가 V15 인덱스의 이름을 이어받는다.
--
-- 이름 · 키 열은 V15 와 같다 — 마커 · 묶음 질의의 좌표 조건과 매퍼 주석 · 테스트가 이 이름을 그대로 쓴다. V19 가 판정 표 인덱스를
-- 교체한 것과 같은 순서다(새 것을 먼저 만들고 옛 것을 지운 뒤 이름을 옮긴다 — 사이에 좌표 인덱스가 빠지는 순간이 없다).
--
-- 잠금 — DROP INDEX 는 매물 표에 ACCESS EXCLUSIVE(읽기까지 막는다)를 잡는다. 인덱스 삭제는 파일 정리라 순간이지만, 잠금을 얻으려면
-- 돌고 있는 매물 질의가 끝나기를 기다리고 그동안 뒤의 매물 읽기가 줄을 선다. standby 도 이 잠금을 재생하는 순간 매물 읽기가 막힌다.
-- ALTER INDEX … RENAME 은 인덱스에 SHARE UPDATE EXCLUSIVE(PostgreSQL 12 이상)다. 둘 다 이 파일의 트랜잭션 하나에서 짧게 끝난다.
-- DROP INDEX CONCURRENTLY 로 나누지 않은 이유 — 그러면 RENAME 이 다른 파일(트랜잭션)이 되어, 둘 사이에 기동이 실패하면 이름 없는
-- 커버링 인덱스만 남는다. 여기서는 둘이 함께 커밋되거나 함께 되돌려진다 — 실패하면 다시 기동해 V25 부터 돈다.
--
-- lock_timeout 5s — V19 와 같은 이유. 운영 기본값은 0(무한 대기)이고, 강한 잠금을 기다리는 동안 매물 읽기가 쌓이므로 오래 기다리지
-- 않고 실패시켜 배포를 멈춘다.

SET LOCAL lock_timeout = '5s';

DROP INDEX idx_property_district_lat_lng;

ALTER INDEX idx_property_district_lat_lng_cov RENAME TO idx_property_district_lat_lng;
