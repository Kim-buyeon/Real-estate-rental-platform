package com.duri.rentalplatform.domain.property.enums;

/**
 * {@code property_code.code_group} 값. 시드는 {@code V2__seed_property_code.sql} 이 넣는다.
 *
 * <p>코드 테이블을 조회할 때 그룹명을 문자열 리터럴로 흘리지 않기 위해 둔다.
 */
public enum CodeGroup {
    CONTRACT_TYPE,
    PROPERTY_TYPE,
    PROPERTY_STATUS
}
