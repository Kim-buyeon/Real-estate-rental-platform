-- 관심 매물(PROP-05) — 한 사용자는 한 매물을 한 번만 등록한다.
--
-- 등록 서비스가 먼저 존재 여부를 확인하지만, 두 인스턴스가 같은 요청을 동시에 받으면 둘 다 확인을 통과한다.
-- 실제로 막는 것은 이 제약이고, 위반은 서비스가 409 WISHLIST_DUPLICATED 로 바꾼다.
--
-- 선행 컬럼이 user_id 라 유일 인덱스가 사용자별 목록 조회 · 해제의 인덱스를 겸한다. 별도 인덱스를 두지 않는다.
ALTER TABLE wishlist
    ADD CONSTRAINT uq_wishlist_user_property UNIQUE (user_id, property_id);
