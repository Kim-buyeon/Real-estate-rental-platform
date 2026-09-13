package com.duri.rentalplatform.domain.admin.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.CursorCodec;
import com.duri.rentalplatform.common.CursorPage;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.admin.dto.condition.CriteriaHistoryCondition;
import com.duri.rentalplatform.domain.admin.dto.request.CriteriaHistoryRequest;
import com.duri.rentalplatform.domain.admin.dto.response.CriteriaHistoryResponse;
import com.duri.rentalplatform.domain.admin.dto.response.GuaranteeCriteriaResponse;
import com.duri.rentalplatform.domain.admin.dto.response.PremiumRatesResponse;
import com.duri.rentalplatform.domain.admin.dto.response.RiskThresholdResponse;
import com.duri.rentalplatform.domain.admin.mapper.CriteriaMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 판정 기준 관리 조회 — API 명세서(관리자) 1.1 · 1.2. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CriteriaQueryService {

    /** 커서를 만든 정렬. 다른 목록의 커서를 거부하는 서명이다. */
    static final String HISTORY_SORT = "changedAt:desc";

    private final CriteriaMapper criteriaMapper;

    public GuaranteeCriteriaResponse getGuaranteeCriteria() {
        return new GuaranteeCriteriaResponse(criteriaMapper.selectGuaranteeCriteria());
    }

    public PremiumRatesResponse getPremiumRates() {
        return new PremiumRatesResponse(criteriaMapper.selectPremiumRates());
    }

    /** 단일 행은 시드(V7)가 넣는다. 없으면 배포 결함이라 500 으로 드러낸다. */
    public RiskThresholdResponse getRiskThreshold() {
        RiskThresholdResponse threshold = criteriaMapper.selectRiskThreshold();
        if (threshold == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return threshold;
    }

    /**
     * 이력 한 페이지. 커서 값은 마지막 행의 변경 시각을 서울 벽시계로 담는다 — 저장 컬럼이 서울 벽시계 TIMESTAMP 라
     * 같은 표현으로 비교해야 동률 행이 빠지거나 겹치지 않는다.
     */
    public CursorPage<CriteriaHistoryResponse> getHistory(CriteriaHistoryRequest request) {
        LocalDateTime lastChangedAt = null;
        Long lastId = null;
        if (request.cursor() != null && !request.cursor().isBlank()) {
            CursorCodec.Cursor cursor = CursorCodec.decode(request.cursor());
            if (!HISTORY_SORT.equals(cursor.s()) || cursor.v() == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
            }
            try {
                lastChangedAt = LocalDateTime.parse(cursor.v());
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
            }
            lastId = cursor.id();
        }

        int size = request.size();
        List<CriteriaHistoryResponse> rows =
                criteriaMapper.selectHistory(new CriteriaHistoryCondition(lastChangedAt, lastId, size + 1));
        boolean hasNext = rows.size() > size;
        List<CriteriaHistoryResponse> items = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = null;
        if (hasNext) {
            CriteriaHistoryResponse last = items.get(items.size() - 1);
            // 응답 시각은 이미 서울 오프셋이라 벽시계만 떼면 저장 값과 같다.
            nextCursor = CursorCodec.encode(HISTORY_SORT, last.changedAt().toLocalDateTime().toString(),
                    last.historyId());
        }
        return new CursorPage<>(List.copyOf(items), nextCursor, hasNext);
    }
}
