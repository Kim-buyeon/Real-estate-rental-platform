package com.duri.rentalplatform.domain.admin.mapper;

import com.duri.rentalplatform.domain.admin.dto.condition.CriteriaHistoryCondition;
import com.duri.rentalplatform.domain.admin.dto.response.CriteriaHistoryResponse;
import com.duri.rentalplatform.domain.admin.dto.response.GuaranteeCriteriaResponse;
import com.duri.rentalplatform.domain.admin.dto.response.PremiumRatesResponse;
import com.duri.rentalplatform.domain.admin.dto.response.RiskThresholdResponse;
import java.util.List;

/** 판정 기준 관리 조회. XML 은 {@code resources/mapper/admin/CriteriaMapper.xml}. */
public interface CriteriaMapper {

    /** 기관별 기준. 식별자 순. */
    List<GuaranteeCriteriaResponse.Provider> selectGuaranteeCriteria();

    /** 보증료율 전 구간. 기관 · 주택 유형 · 보증금 하한 · 부채비율 하한 순. */
    List<PremiumRatesResponse.Item> selectPremiumRates();

    /** 위험 등급 기준(단일 행). 없으면 null. */
    RiskThresholdResponse selectRiskThreshold();

    /** 변경 이력 한 페이지(요청 크기 + 1). {@code changedAt DESC, historyId DESC}. */
    List<CriteriaHistoryResponse> selectHistory(CriteriaHistoryCondition condition);
}
