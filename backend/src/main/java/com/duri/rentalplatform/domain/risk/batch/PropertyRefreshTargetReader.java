package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.property.dto.condition.UnanalyzedPropertyCondition;
import com.duri.rentalplatform.domain.property.mapper.PropertyMapper;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshReport;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshTarget;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.batch.infrastructure.item.ItemReader;

/**
 * 매물 갱신 배치(RISK-08) 판정 스텝의 읽기 단계. 두 갈래를 차례로 내준다.
 *
 * <ol>
 *   <li><b>시세가 바뀐 매물</b> — 적재 단계가 회차 집계에 남긴 식별자. 재분석 대상이다</li>
 *   <li><b>최신 판정이 없는 매물</b> — 매퍼가 NOT EXISTS 로 거른 식별자를 식별자 커서로 한 페이지씩. 이번 회차의 신규 매물이
 *       여기서 나오고, 앞 회차에 판정이 실패해 남은 매물도 함께 나온다 — 실패한 매물이 다음 날 다시 채워진다. 전체 매물을
 *       메모리에 올리지 않는다</li>
 * </ol>
 *
 * <p><b>겹침</b> — 시세가 바뀐 매물에 최신 판정이 없으면 두 갈래에 모두 든다. 두 번째 갈래에서는 첫 갈래에서 내준 식별자를
 * 건너뛴다 — 첫 갈래에서 판정이 실패했으면 같은 회차에 다시 시도하지 않고 다음 회차에 맡긴다. 두 번째 갈래는 첫 갈래를 다 내준
 * 뒤에 조회하므로, 첫 갈래에서 판정된 매물은 대부분 이미 조회되지 않는다.
 *
 * <p><b>커서</b> — 이전 페이지의 마지막 식별자가 다음 조회 조건이다. 앞 페이지에서 판정된 매물은 조회에서 빠지지만 커서가 식별자라
 * 건너뛰거나 다시 읽지 않는다. 페이지 크기보다 적게 오면 마지막 페이지로 보고 더 조회하지 않는다.
 *
 * <p><b>상태</b> — 빈이 아니며 회차마다 새로 만든다. 시세 변경 식별자는 첫 읽기 때 집계에서 꺼낸다 — 읽기 단계는 적재 스텝보다
 * 먼저 만들어지므로 만들 때는 아직 비어 있다.
 */
public class PropertyRefreshTargetReader implements ItemReader<PropertyRefreshTarget> {

    private final PropertyMapper propertyMapper;
    private final PropertyRefreshReport report;
    private final int pageSize;

    private Iterator<Long> priceChanged;
    private Set<Long> priceChangedIds;
    private Iterator<Long> unanalyzedPage = Collections.emptyIterator();
    private Long lastPropertyId;
    private boolean lastPageRead;

    public PropertyRefreshTargetReader(PropertyMapper propertyMapper, PropertyRefreshReport report, int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("페이지 크기는 1 이상이어야 한다: " + pageSize);
        }
        this.propertyMapper = propertyMapper;
        this.report = report;
        this.pageSize = pageSize;
    }

    /** 다음 대상. 더 없으면 null — 스텝은 null 을 읽기 끝으로 본다. */
    @Override
    public PropertyRefreshTarget read() {
        if (priceChanged == null) {
            List<Long> ids = report.getPriceChangedPropertyIds();
            priceChangedIds = new HashSet<>(ids);
            priceChanged = ids.iterator();
        }
        if (priceChanged.hasNext()) {
            return PropertyRefreshTarget.priceChanged(priceChanged.next());
        }
        Long next = nextUnanalyzed();
        while (next != null && priceChangedIds.contains(next)) {
            next = nextUnanalyzed();
        }
        return next == null ? null : PropertyRefreshTarget.unanalyzed(next);
    }

    private Long nextUnanalyzed() {
        if (!unanalyzedPage.hasNext()) {
            if (lastPageRead) {
                return null;
            }
            List<Long> propertyIds = propertyMapper.selectUnanalyzedPropertyIds(
                    new UnanalyzedPropertyCondition(lastPropertyId, pageSize));
            lastPageRead = propertyIds.size() < pageSize;
            if (propertyIds.isEmpty()) {
                return null;
            }
            lastPropertyId = propertyIds.getLast();
            unanalyzedPage = propertyIds.iterator();
        }
        return unanalyzedPage.next();
    }
}
