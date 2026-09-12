package com.duri.rentalplatform.domain.property.repository;

import com.duri.rentalplatform.domain.property.entity.Property;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 매물 저장과 엔티티 조회. 화면에 전달할 목록 조회는 여기가 아니라 매퍼가 맡는다 —
 * 아키텍처 설계서(영속성 구조) 1.1.
 */
public interface PropertyRepository extends JpaRepository<Property, Long> {

    /**
     * 자치구 하나의 적재분을 전부 읽는다. 자연키 집합을 만들어 중복 적재를 거르는 용도다.
     *
     * <p>건마다 존재 여부를 묻지 않는 이유는 호출 횟수다. 한 자치구 수천 건에 대해 건별 조회를 하면
     * 왕복이 그만큼 늘어난다. 적재는 자치구 단위로 도므로 한 번 읽어 메모리에서 비교한다.
     */
    List<Property> findAllByDistrict(String district);
}
