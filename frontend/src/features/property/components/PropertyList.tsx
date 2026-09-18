import { useInfiniteQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import type { PropertyFilter } from '../../../api/property';
import { Alert, Badge, Button, Field, Select } from '../../../components/ui';
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

interface PropertyListProps {
  /** 지도와 같은 필터다 — 목록 전용 조건을 따로 두지 않는다 (매물 API 명세 1.1) */
  filter: PropertyFilter;
  onSelect: (propertyId: number) => void;
}

/**
 * 매물 목록 (PROP-01). 데이터를 부르는 컴포넌트다.
 *
 * 좌표를 보내지 않는 조회라 응답이 목록 형태로 온다 — 같은 엔드포인트가 좌표 유무로 형태를 가른다
 * (명세 1.3). 지도의 표시 영역과 무관하며, 좁히는 것은 필터의 자치구다.
 *
 * 커서 목록이라 「더 보기」로 다음 쪽을 잇는다 — 전체 건수는 주지 않는다 (공통 규약 1.4).
 * 정렬은 화면 상태로 여기가 갖는다. 고르지 않은 상태에서는 값을 보내지 않고, 그때의 순서(등록일
 * 내림차순)는 서버가 만든다 (명세 1.3) — 기본값을 화면에 다시 적지 않는다.
 *
 * 등급 · 전세가율은 서버 값을 표시만 한다. 미분석(null) 문구도 domain/risk.ts가 갖는다.
 */
export function PropertyList({ filter, onSelect }: PropertyListProps) {
  const [sort, setSort] = useState<PropertySort | undefined>(undefined);
  // 필터와 정렬이 쿼리 키에 들어간다 — 조건이 바뀌면 커서도 처음부터 다시 쌓인다 (queries/property.ts)
  const listQuery = useInfiniteQuery(propertyQueries.list(filter, sort));

  // 쪽마다 나뉜 항목을 한 배열로 잇는다. 렌더마다 다시 만들지 않는다
  const items = useMemo(() => listQuery.data?.pages.flatMap((page) => page.items) ?? [], [listQuery.data]);

  return (
    <div className={styles.list}>
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

      <div className={styles.body}>
        {listQuery.isPending && <p className="type-body">불러오는 중입니다.</p>}

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
               */
              <li key={item.propertyId} className={styles.item}>
                <div className={styles.head}>
                  <p className={`${styles.price} type-heading-3`}>
                    {formatWon(item.deposit)}
                    {item.monthlyRent > 0 && ` / ${formatWon(item.monthlyRent)}`}
                  </p>
                  <Badge variant={riskGradeToken(item.riskGrade)}>{riskGradeLabel(item.riskGrade)}</Badge>
                </div>

                <p className={`${styles.kind} type-body`}>
                  {contractTypeLabel(item.contractType)} · {propertyTypeLabel(item.propertyType)}
                </p>

                <p className={`${styles.spec} type-body-sm`}>{item.address}</p>

                <p className={`${styles.spec} type-body-sm`}>
                  {item.district} · {item.areaSqm}㎡ · {item.floor}층 · 전세가율 {debtRatioLabel(item.debtRatio)} ·{' '}
                  {formatDate(item.registeredAt)} 등록
                </p>

                <div className={styles.actions}>
                  {/* 주소를 이름에 넣는다 — 「상세 보기」가 목록에 여럿이라 그것만으로는 구분되지 않는다 */}
                  <Button
                    type="button"
                    size="sm"
                    aria-label={`${item.address} 상세 보기`}
                    onClick={() => onSelect(item.propertyId)}
                  >
                    상세 보기
                  </Button>
                </div>
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
      </div>
    </div>
  );
}
