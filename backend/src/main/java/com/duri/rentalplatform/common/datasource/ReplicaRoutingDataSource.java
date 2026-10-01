package com.duri.rentalplatform.common.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * 커넥션을 기본 풀과 읽기용 풀 중에서 고른다. {@link ReplicaRead} 구간이면 읽기용, 아니면 기본이다. 빈으로 두지 않는다 — 데이터
 * 소스 설정이 만들어 {@code LazyConnectionDataSourceProxy} 로 감싼다. 감싸는 이유는 트랜잭션 시작이 아니라 첫 조회 시점에
 * 커넥션을 고르게 하려는 것이다.
 *
 * <p>키는 문자열이다 — 건강 확인({@code /actuator/health} 의 db)이 키 이름으로 두 풀을 나눠 보인다.
 */
public class ReplicaRoutingDataSource extends AbstractRoutingDataSource {

    public static final String PRIMARY = "primary";
    public static final String REPLICA = "replica";

    @Override
    protected Object determineCurrentLookupKey() {
        return ReplicaReadContext.isActive() ? REPLICA : PRIMARY;
    }
}
