import { useInfiniteQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { BoundingBox, PropertyFilter } from '../../../api/property';
import { Alert, Badge, Button, Field, Select, Skeleton, visuallyHiddenClassName } from '../../../components/ui';
import {
  PROPERTY_SORTS,
  PROPERTY_SORT_DEFAULT_LABEL,
  PROPERTY_SORT_LABEL,
  contractTypeLabel,
  propertyTypeLabel,
  type PropertySort,
} from '../../../domain/property';
import { debtRatioLabel, riskGradeLabel, riskGradeToken } from '../../../domain/risk';
import { formatDate, formatWon } from '../../../lib/format';
import { propertyQueries } from '../../../queries/property';
import styles from './PropertyList.module.css';

/** 첫 로딩에 자리를 잡아 둘 행 수. 목록 패널 한 화면에 보이는 정도다 — 실제 건수와 무관하다 */
const SKELETON_COUNT = 5;

/**
 * 칸 목록 — 지도에서 더 확대할 수 없는 묶음 칸이나 같은 좌표에 겹친 마커를 골랐을 때 목록을 그 영역으로 좁힌다.
 * bbox는 요청에 그대로 나가는 표시 영역(명세 1.3 「목록의 표시 영역」)이다. 건수는 두지 않는다 — 지도 응답의 숫자
 * (묶음 count · 겹친 마커 수)를 화면 상태에 복사하면 서버 값과 어긋날 수 있다(서버 상태는 쿼리가 갖는다).
 */
export interface PropertyListArea {
  bbox: BoundingBox;
}

interface PropertyListProps {
  /** 지도와 같은 필터다 — 목록 전용 조건을 따로 두지 않는다 (매물 API 명세 1.1) */
  filter: PropertyFilter;
  /** 칸 목록의 영역. 없으면(null · 생략) 필터 전체의 목록이다 */
  area?: PropertyListArea | null;
  /** 「전체 목록」 — 칸 목록을 해제한다. area를 주는 쪽이 함께 준다 */
  onClearArea?: () => void;
  onSelect: (propertyId: number) => void;
}

/**
 * 매물 목록 (PROP-01). 데이터를 부르는 컴포넌트다.
 *
 * 반경 조건을 보내지 않는 조회라 응답이 목록 형태로 온다 — 같은 엔드포인트가 반경 유무로 형태를 가른다
 * (명세 1.3). 평소에는 지도의 표시 영역과 무관하며, 좁히는 것은 필터의 자치구다. area가 오면(칸 목록) 그 영역
 * 네 값을 함께 보내 그 안의 매물만 받는다 — 지도를 움직여도 따라가지 않고, 「전체 목록」이나 자치구 변경에서 풀린다.
 *
 * 커서 목록이라 다음 쪽을 잇는다 — 전체 건수는 주지 않는다 (공통 규약 1.4). 패널 스크롤이 목록 끝에 닿으면
 * 다음 쪽을 받고, 「더 보기」 버튼은 키보드 사용자와 감시가 안 되는 환경을 위해 남긴다.
 * 정렬은 화면 상태로 여기가 갖는다. 고르지 않은 상태에서는 값을 보내지 않고, 그때의 순서(등록일
 * 내림차순)는 서버가 만든다 (명세 1.3) — 기본값을 화면에 다시 적지 않는다.
 *
 * 등급 · 전세가율은 서버 값을 표시만 한다. 미분석(null) 문구도 domain/risk.ts가 갖는다.
 */
export function PropertyList({ filter, area = null, onClearArea, onSelect }: PropertyListProps) {
  const [sort, setSort] = useState<PropertySort | undefined>(undefined);
  // 필터 · 정렬 · 칸 영역이 쿼리 키에 들어간다 — 조건이 바뀌면 커서도 처음부터 다시 쌓인다 (queries/property.ts)
  const listQuery = useInfiniteQuery(propertyQueries.list(filter, sort, area?.bbox));

  // 쪽마다 나뉜 항목을 한 배열로 잇는다. 렌더마다 다시 만들지 않는다
  const items = useMemo(() => listQuery.data?.pages.flatMap((page) => page.items) ?? [], [listQuery.data]);

  // 목록 끝 감시. 스크롤 영역(.body)을 root 로 두어, 패널 안에서 끝에 닿았을 때만 다음 쪽을 받는다
  const scrollRef = useRef<HTMLDivElement>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const { hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = listQuery;
  useEffect(() => {
    const root = scrollRef.current;
    const sentinel = sentinelRef.current;
    // 감시가 없는 환경(jsdom · 구형 브라우저)에서는 아무것도 하지 않는다 — 「더 보기」 버튼이 남아 있다
    if (typeof IntersectionObserver === 'undefined' || !root || !sentinel || !hasNextPage) return;
    // 다음 쪽 요청이 실패했으면 자동으로 다시 부르지 않는다. 감시를 새로 걸면 observe 직후 알림으로 곧바로
    // 다시 요청해 실패 → 재요청이 끝없이 돈다(재시도 상한 우회). 다시 시도는 「더 보기」 버튼으로만 —
    // 그것이 성공하면 이 값이 풀려 감시가 다시 걸린다
    if (isFetchNextPageError) return;
    const observer = new IntersectionObserver(
      (entries) => {
        // 받는 중에는 다시 부르지 않는다 — 감시 요소가 계속 보여도 쪽 요청은 한 번이다
        if (entries.some((entry) => entry.isIntersecting) && !isFetchingNextPage) {
          void fetchNextPage();
        }
      },
      { root },
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage]);

  return (
    <div className={styles.list}>
      {area && (
        <div className={styles.areaNotice}>
          <Alert variant="info" className={styles.areaAlert}>
            <span>이 위치의 매물을 보고 있습니다</span>
            {onClearArea && (
              <Button type="button" variant="secondary" size="sm" onClick={onClearArea}>
                전체 목록
              </Button>
            )}
          </Alert>
        </div>
      )}

      <div className={styles.toolbar}>
        <Field label="정렬">
          {(control) => (
            <Select
              {...control}
              value={sort ?? ''}
              onChange={(event) => setSort(event.target.value ? (event.target.value as PropertySort) : undefined)}
            >
              {/* 값 없음이 기본 정렬이다 — 서버가 등록일 내림차순으로 준다 (명세 1.3) */}
              <option value="">{PROPERTY_SORT_DEFAULT_LABEL}</option>
              {PROPERTY_SORTS.map((option) => (
                <option key={option} value={option}>
                  {PROPERTY_SORT_LABEL[option]}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>

      <div ref={scrollRef} className={styles.body}>
        {/* 첫 로딩은 목록 행 모양의 자리 표시다({components.skeleton}) — 관심 매물 · 알림 목록과 같은 방식 */}
        {listQuery.isPending && (
          <Skeleton count={SKELETON_COUNT} className={styles.skeleton} itemClassName={styles.skeletonItem} />
        )}

        {listQuery.error && <Alert variant="error">{listQuery.error.message}</Alert>}

        {!listQuery.isPending && !listQuery.error && items.length === 0 && (
          <Alert variant="info">조건에 맞는 매물이 없습니다. 필터를 바꾸거나 자치구를 다시 골라 보세요.</Alert>
        )}

        {items.length > 0 && (
          <ul className={styles.items}>
            {items.map((item) => (
              /*
               * 목록 행 — 레이아웃 맵 map-search 「list-panel 내부」. 썸네일은 만들지 않는다
               * (이미지 데이터가 없다). 위계는 맵 그대로 가격 → 유형 → 스펙이다.
               * 행 자체가 눌린다 — 행 안에 따로 「상세 보기」 버튼을 두지 않는다(이슈 489). 상세는 화면 이동이 아니라
               * 지도 옆 패널이라 링크가 아니라 버튼이다. 버튼 안에는 문단(<p>)을 둘 수 없어 줄은 전부 <span>이다.
               */
              <li key={item.propertyId} className={styles.item}>
                <button
                  type="button"
                  className={styles.row}
                  onClick={() => onSelect(item.propertyId)}
                >
                  <span className={styles.head}>
                    <span className={`${styles.price} type-heading-3`}>
                      {formatWon(item.deposit)}
                      {item.monthlyRent > 0 && ` / ${formatWon(item.monthlyRent)}`}
                    </span>
                    <Badge variant={riskGradeToken(item.riskGrade)}>{riskGradeLabel(item.riskGrade)}</Badge>
                  </span>

                  <span className={`${styles.kind} type-body`}>
                    {contractTypeLabel(item.contractType)} · {propertyTypeLabel(item.propertyType)}
                  </span>

                  <span className={`${styles.spec} type-body-sm`}>{item.address}</span>

                  <span className={`${styles.spec} type-body-sm`}>
                    {item.district} · {item.areaSqm}㎡ · {item.floor}층 · 전세가율 {debtRatioLabel(item.debtRatio)} ·{' '}
                    {formatDate(item.registeredAt)} 등록
                  </span>

                  {/*
                    접근 이름은 행의 글자 전부 + 「상세 보기」다. aria-label 을 두면 가격 · 등급 · 유형 · 스펙이
                    이름에서 빠진다 — 행이 여럿이라 주소 · 가격으로 구분되고, 끝말로 무엇을 하는 버튼인지 알린다
                  */}
                  <span className={visuallyHiddenClassName}>상세 보기</span>
                </button>
              </li>
            ))}
          </ul>
        )}

        {listQuery.hasNextPage && (
          <Button
            type="button"
            variant="secondary"
            onClick={() => void listQuery.fetchNextPage()}
            isLoading={listQuery.isFetchingNextPage}
          >
            더 보기
          </Button>
        )}

        {/* 목록 끝 감시 요소 — 보이면 다음 쪽을 받는다. 자리를 차지하지 않는다 */}
        {listQuery.hasNextPage && <div ref={sentinelRef} className={styles.sentinel} aria-hidden="true" />}
      </div>
    </div>
  );
}
