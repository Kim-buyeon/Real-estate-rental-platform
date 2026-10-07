package com.duri.rentalplatform.domain.property.repository;

import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 매물 저장과 엔티티 조회. 화면에 전달할 목록 조회는 여기가 아니라 매퍼가 맡는다 —
 * 아키텍처 설계서(영속성 구조) 1.1.
 */
public interface PropertyRepository extends JpaRepository<Property, Long> {

    /**
     * 매물 유형 코드를 함께 읽는다. 호출부가 트랜잭션 밖(대출 한도 조회)이라 지연 로딩이 닿지 않는다 — 조인으로 한 번에 가져온다.
     */
    @EntityGraph(attributePaths = "propertyTypeCode")
    Optional<Property> findWithPropertyTypeCodeByPropertyId(Long propertyId);

    /**
     * 판정 입력(등기 · 대장)이 바뀌었다 — 재분석 대기 표시(V18)를 세운다. 등기 · 대장을 바꾸는 쓰기와 같은 트랜잭션에서 부른다. 바꾼
     * 행 수(이미 서 있으면 0).
     *
     * <p><b>벌크 UPDATE 인 이유</b> — 조건(지금 값)을 걸어 한 문장으로 바꾼다. 엔티티로 바꾸면 읽은 뒤 커밋까지 사이에 다른
     * 트랜잭션이 같은 열을 바꾼 것을 모른 채 덮는다({@code @DynamicUpdate} 는 다른 열을 덮지 않게 할 뿐, 같은 열의 경합은 막지
     * 않는다). 영속성 컨텍스트를 우회하므로 같은 트랜잭션에서 이미 읽은 매물 엔티티의 표시 값은 갱신되지 않는다.
     */
    @Modifying
    @Query("UPDATE Property p SET p.reanalysisPending = true WHERE p.propertyId = :propertyId AND p.reanalysisPending = false")
    int markReanalysisPending(@Param("propertyId") Long propertyId);

    /**
     * 판정을 마쳐 기록했다 — 재분석 대기 표시를 내린다. 판정 기록과 같은 쓰기 트랜잭션에서 부른다. 바꾼 행 수(이미 내려가 있거나
     * 시세가 달라졌으면 0). 벌크 UPDATE 인 이유는 {@link #markReanalysisPending} 과 같다.
     *
     * @param judgedMarketPrice 판정에 쓴 시세. 판정하는 사이 갱신 배치가 시세를 바꾸고 표시를 세웠으면 그 표시는 내리지 않는다 — 내리면
     *                          옛 시세로 낸 판정이 새 시세의 판정인 것처럼 남는다
     */
    @Modifying
    @Query("UPDATE Property p SET p.reanalysisPending = false WHERE p.propertyId = :propertyId"
            + " AND p.reanalysisPending = true AND p.marketPrice = :judgedMarketPrice")
    int clearReanalysisPending(@Param("propertyId") Long propertyId,
            @Param("judgedMarketPrice") Long judgedMarketPrice);

    /**
     * 최신 판정의 등급 · 전세가율을 매물의 비정규화 열(V22)에 옮긴다. 판정 기록의 쓰기 트랜잭션에서 늘 부른다(결론이 같아도) —
     * 판정 행과 함께 커밋되거나 함께 롤백된다. 바꾼 행 수(이미 같으면 0).
     *
     * <p>값이 이미 같으면 쓰지 않는다 — 행의 새 판과 WAL 을 만들지 않는다. 벌크 UPDATE 인 이유는 {@link #markReanalysisPending}
     * 과 같고, 엔티티는 이 열을 바꾸지 않는다({@code Property} 주석). 같은 매물의 판정 기록 둘은 최신 판정 행의 행 잠금
     * ({@code RiskAnalysisRepository#findLatestForUpdate})에서 줄을 서고, 최신 행이 없으면 최신 행 유일 인덱스와 재시도가 가른다. 그래서
     * 나중에 기록하는 쪽은 먼저 쪽이 커밋한 최신 행을 보고 쓴다 — 매물 열이 판정 표의 최신 행과 같은 순서로 바뀐다.
     *
     * @param leaseRatio 저장한 판정 행의 전세가율. 열이 NUMERIC(5,2) 로 판정 표와 같아 같은 값으로 반올림된다
     */
    @Modifying
    @Query("UPDATE Property p SET p.riskGrade = :riskGrade, p.leaseRatio = :leaseRatio"
            + " WHERE p.propertyId = :propertyId"
            + " AND (p.riskGrade IS NULL OR p.leaseRatio IS NULL"
            + " OR p.riskGrade <> :riskGrade OR p.leaseRatio <> :leaseRatio)")
    int applyLatestJudgement(@Param("propertyId") Long propertyId,
            @Param("riskGrade") RiskGrade riskGrade,
            @Param("leaseRatio") BigDecimal leaseRatio);
}
