package com.duri.rentalplatform.domain.property.repository;

import com.duri.rentalplatform.domain.property.entity.PropertyCode;
import com.duri.rentalplatform.domain.property.enums.CodeGroup;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 공통 코드 조회. 값은 시드 마이그레이션이 넣으므로 이 저장소로 코드를 만들거나 고치지 않는다.
 */
public interface PropertyCodeRepository extends JpaRepository<PropertyCode, Long> {

    /** 자연키(code_group + code_value)로 찾는다 — 데이터베이스 설계서 4.3. */
    Optional<PropertyCode> findByCodeGroupAndCodeValue(CodeGroup codeGroup, String codeValue);
}
