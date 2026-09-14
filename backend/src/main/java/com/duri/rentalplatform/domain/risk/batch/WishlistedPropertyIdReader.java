package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.property.dto.condition.WishlistedPropertyCondition;
import com.duri.rentalplatform.domain.property.mapper.WishlistMapper;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.springframework.batch.infrastructure.item.ItemReader;

/**
 * 등기 재조회 배치(RISK-08) 스텝의 읽기 단계. 관심 매물로 등록된 서로 다른 매물 식별자를 식별자 커서로 한 페이지씩 읽어 하나씩 내준다.
 *
 * <p><b>커서</b> — 이전 페이지의 마지막 식별자가 다음 조회 조건이다. OFFSET 을 쓰지 않으므로 배치 도중 관심 매물이 늘거나 줄어도
 * 이미 지난 식별자를 다시 읽거나 건너뛰지 않는다. 페이지 크기보다 적게 오면 마지막 페이지로 보고 더 조회하지 않는다.
 *
 * <p><b>상태</b> — 커서를 필드로 갖는다. 빈이 아니며 회차마다 새로 만든다 — 한 인스턴스를 두 회차가 나눠 쓰면 앞 회차의 커서에서
 * 시작한다. 재시작 상태는 실행 컨텍스트에 남기지 않는다 — resourceless 저장소는 실행 컨텍스트를 보관하지 않고, 이 배치는 다시 돌면
 * 처음부터 읽는다.
 */
public class WishlistedPropertyIdReader implements ItemReader<Long> {

    private final WishlistMapper wishlistMapper;
    private final int pageSize;

    private Iterator<Long> page = Collections.emptyIterator();
    private Long lastPropertyId;
    private boolean lastPageRead;

    public WishlistedPropertyIdReader(WishlistMapper wishlistMapper, int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("페이지 크기는 1 이상이어야 한다: " + pageSize);
        }
        this.wishlistMapper = wishlistMapper;
        this.pageSize = pageSize;
    }

    /** 다음 매물 식별자. 더 없으면 null — 스텝은 null 을 읽기 끝으로 본다. */
    @Override
    public Long read() {
        if (!page.hasNext()) {
            if (lastPageRead) {
                return null;
            }
            List<Long> propertyIds = wishlistMapper.selectWishlistedPropertyIds(
                    new WishlistedPropertyCondition(lastPropertyId, pageSize));
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
