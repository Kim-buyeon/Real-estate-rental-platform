package com.duri.rentalplatform.domain.loan.repository;

import com.duri.rentalplatform.domain.loan.entity.LoanProduct;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 대출 상품 조회 · 갱신. 상품 선택 · 추천(LOAN-03)은 차기이며, 1단계는 한도 계산의 금리 · 상품 한도 입력으로 대표 상품 한 행을 쓴다.
 *
 * <p><b>대표 상품 선택</b> — 매물 유형이 같은 HF 금리 API 행 중 기준월이 가장 늦은 달에서 대출실행금액이 가장 큰 은행. 금액이 같으면
 * 금리가 낮은 쪽, 그래도 같으면 먼저 들어온 행. 그 매물 유형의 API 행이 없으면 V10 시드 예시 행(주택 유형 없음)을 쓴다.
 */
public interface LoanProductRepository extends JpaRepository<LoanProduct, Long> {

    /** 매물 유형의 대표 상품 — 최신 기준월 · 실행금액 최대. 기준월이 지난 은행(최신 달에 실적 없음)은 자연히 밀린다. */
    Optional<LoanProduct> findFirstByHouseTypeOrderByBaseMonthDescLoanAmountDescInterestRateAscLoanIdAsc(
            PropertyType houseType);

    /** API 행이 없을 때 쓰는 시드 예시 행. */
    Optional<LoanProduct> findFirstByHouseTypeIsNullOrderByLoanIdAsc();

    /** 갱신 대상 — 한 매물 유형의 API 행 전부. 은행 수만큼(20 안쪽)이라 한 번에 읽어 은행명으로 맞춘다. */
    List<LoanProduct> findAllByHouseType(PropertyType houseType);

    /** 기동 시 확인 — 그 매물 유형에 기준월 이후의 행이 있는가. */
    boolean existsByHouseTypeAndBaseMonthGreaterThanEqual(PropertyType houseType, LocalDate baseMonth);
}
