package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.property.dto.condition.PriceChangedPropertyCondition;
import com.duri.rentalplatform.domain.property.dto.condition.UnanalyzedPropertyCondition;
import com.duri.rentalplatform.domain.property.mapper.PropertyMapper;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshTarget;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiFunction;
import org.springframework.batch.infrastructure.item.ItemReader;

/**
 * 매물 갱신 배치(RISK-08) 판정 스텝의 읽기 단계. 두 갈래를 차례로, 둘 다 DB 에서 식별자 커서로 한 페이지씩 내준다.
 *
 * <ol>
 *   <li><b>시세가 바뀐 매물</b> — 적재가 재분석 대기로 표시한 매물(V18). 재분석 대상이다. 판정을 마치면 표시가 내려간다. 이번 회차 적재가 바꾼 매물과,
 *       앞 회차에 재분석이 실패 · 경합으로 끝나 남은 매물이 함께 나온다</li>
 *   <li><b>최신 판정이 없는 매물</b> — 이번 회차의 신규 매물과, 앞 회차에 첫 판정이 실패해 남은 매물</li>
 * </ol>
 *
 * <p><b>메모리</b> — 한 번에 한 페이지(페이지 크기만큼의 식별자)만 든다. 적재 단계가 식별자를 모아 넘기던 방식은 신규 · 시세 변경
 * 매물 수만큼 회차 내내 목록이 남았다(2026-09-30 운영 회차, 신규 23만여 건).
 *
 * <p><b>겹침</b> — 없다. 둘째 갈래는 재분석 대기 매물을 빼고 조회한다.
 *
 * <p><b>커서</b> — 이전 페이지의 마지막 식별자가 다음 조회 조건이다. 앞 페이지에서 판정된 매물은 조회에서 빠지지만 커서가 식별자라
 * 건너뛰거나 다시 읽지 않는다. 페이지 크기보다 적게 오면 그 갈래의 마지막 페이지로 보고 더 조회하지 않는다. 첫 갈래를 다 내준 뒤에
 * 둘째 갈래를 조회한다.
 *
 * <p><b>상태</b> — 빈이 아니며 회차마다 새로 만든다. 만들 때 조회하지 않는다 — 읽기 단계는 적재 스텝보다 먼저 만들어지므로, 첫
 * 읽기 때 조회해야 적재가 남긴 시세 변경 · 신규 매물이 보인다.
 */
public class PropertyRefreshTargetReader implements ItemReader<PropertyRefreshTarget> {

    private final IdCursor priceChanged;
    private final IdCursor unanalyzed;

    public PropertyRefreshTargetReader(PropertyMapper propertyMapper, int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("페이지 크기는 1 이상이어야 한다: " + pageSize);
        }
        this.priceChanged = new IdCursor(pageSize, (lastId, limit) ->
                propertyMapper.selectPriceChangedPropertyIds(new PriceChangedPropertyCondition(lastId, limit)));
        this.unanalyzed = new IdCursor(pageSize, (lastId, limit) ->
                propertyMapper.selectUnanalyzedPropertyIds(new UnanalyzedPropertyCondition(lastId, limit)));
    }

    /** 다음 대상. 더 없으면 null — 스텝은 null 을 읽기 끝으로 본다. */
    @Override
    public PropertyRefreshTarget read() {
        Long next = priceChanged.next();
        if (next != null) {
            return PropertyRefreshTarget.priceChanged(next);
        }
        next = unanalyzed.next();
        return next == null ? null : PropertyRefreshTarget.unanalyzed(next);
    }

    /** 식별자 오름차순 커서 한 갈래. 한 페이지만 든다. */
    private static final class IdCursor {

        private final int pageSize;
        private final BiFunction<Long, Integer, List<Long>> fetchPage;

        private Iterator<Long> page = Collections.emptyIterator();
        private Long lastPropertyId;
        private boolean lastPageRead;

        private IdCursor(int pageSize, BiFunction<Long, Integer, List<Long>> fetchPage) {
            this.pageSize = pageSize;
            this.fetchPage = fetchPage;
        }

        private Long next() {
            if (!page.hasNext()) {
                if (lastPageRead) {
                    return null;
                }
                List<Long> propertyIds = fetchPage.apply(lastPropertyId, pageSize);
                lastPageRead = propertyIds.size() < pageSize;
                if (propertyIds.isEmpty()) {
                    return null;
                }
                lastPropertyId = propertyIds.getLast();
                page = propertyIds.iterator();
            }
            return page.next();
        }
    }
}
