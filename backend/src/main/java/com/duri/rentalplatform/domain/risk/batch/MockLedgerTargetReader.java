package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.property.dto.condition.LedgerSourceTargetCondition;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.mapper.LedgerMapper;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDailyQuota;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.batch.infrastructure.item.ItemReader;

/**
 * Mock 대장 교체 배치 스텝의 읽기 단계. Mock 대장을 가진 매물 식별자를 <b>관심 매물 먼저, 그다음 전체를</b> 식별자 커서로 한
 * 페이지씩 읽어 하나씩 내준다.
 *
 * <p><b>관심 매물 먼저</b> — 하루 상한 안에서 누군가 지켜보는 매물의 대장부터 실데이터로 바꾼다. 두 단계로 읽는다 — 관심 매물만,
 * 다음에 전체. 전체 단계는 앞 단계에서 내준 식별자를 건너뛴다 — 앞 단계에서 실패 · 건너뜀으로 Mock 이 남은 매물을 같은 회차에
 * 다시 부르지 않는다. 앞 단계 식별자는 관심 매물 수만큼만 메모리에 둔다(전체 Mock 대장을 올리지 않는다).
 *
 * <p><b>상한에서 끝낸다</b> — 매물을 내주기 전마다 오늘 남은 호출 수를 보고, 0 이면 읽기를 끝낸다(null). 스텝은 청크 하나를
 * 다 읽은 뒤 처리하므로, 처리 도중 상한에 닿으면 그 청크의 남은 매물은 처리 단계를 지나고(실행기가 상한을 보고 떼지 않는다 —
 * 조회 키가 없는 매물은 호출 없이 지운다) 다음 청크의 첫 읽기에서 끝난다. 상한이 소진된 뒤의 다음 청크 매물은 조회 키가 없어도
 * 다음 회차로 넘긴다.
 *
 * <p><b>커서</b> — 이전 페이지의 마지막 식별자가 다음 조회 조건이다. 처리 단계가 앞 페이지의 행을 교체 · 삭제해 Mock 집합에서
 * 빠져도 이미 지난 식별자 뒤에서 이어 읽으므로 건너뛰거나 겹치지 않는다. 페이지 크기보다 적게 오면 그 단계의 마지막 페이지다.
 *
 * <p><b>상태</b> — 커서 · 단계를 필드로 갖는다. 빈이 아니며 회차마다 새로 만든다 — {@link WishlistedPropertyIdReader} 와 같다.
 */
public class MockLedgerTargetReader implements ItemReader<Long> {

    private final LedgerMapper ledgerMapper;
    private final BuildingLedgerDailyQuota dailyQuota;
    private final int pageSize;

    private final Set<Long> wishlistedRead = new HashSet<>();
    private boolean wishlistedPhase = true;
    private Iterator<Long> page = Collections.emptyIterator();
    private Long lastPropertyId;
    private boolean lastPageRead;
    private boolean finished;

    public MockLedgerTargetReader(LedgerMapper ledgerMapper, BuildingLedgerDailyQuota dailyQuota, int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("페이지 크기는 1 이상이어야 한다: " + pageSize);
        }
        this.ledgerMapper = ledgerMapper;
        this.dailyQuota = dailyQuota;
        this.pageSize = pageSize;
    }

    /** 다음 매물 식별자. 더 없거나 오늘 상한을 다 썼으면 null — 스텝은 null 을 읽기 끝으로 본다. */
    @Override
    public Long read() {
        if (finished) {
            return null;
        }
        if (dailyQuota.remaining() <= 0) {
            finished = true;
            return null;
        }
        Long next = nextTarget();
        if (next == null) {
            finished = true;
        }
        return next;
    }

    private Long nextTarget() {
        while (true) {
            while (page.hasNext()) {
                Long propertyId = page.next();
                if (wishlistedPhase) {
                    wishlistedRead.add(propertyId);
                    return propertyId;
                }
                if (!wishlistedRead.contains(propertyId)) {
                    return propertyId;
                }
            }
            if (lastPageRead) {
                if (!wishlistedPhase) {
                    return null;
                }
                // 관심 매물 단계를 끝내고 전체 단계를 처음부터 읽는다.
                wishlistedPhase = false;
                lastPropertyId = null;
                lastPageRead = false;
            }
            List<Long> propertyIds = ledgerMapper.selectPropertyIdsByLedgerSource(new LedgerSourceTargetCondition(
                    LedgerDataSource.MOCK, wishlistedPhase, lastPropertyId, pageSize));
            lastPageRead = propertyIds.size() < pageSize;
            if (!propertyIds.isEmpty()) {
                lastPropertyId = propertyIds.getLast();
            }
            page = propertyIds.iterator();
        }
    }
}
