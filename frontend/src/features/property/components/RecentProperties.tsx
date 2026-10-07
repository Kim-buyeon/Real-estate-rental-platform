import { useInfiniteQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { Link } from 'react-router';
import type { PropertyFilter } from '../../../api/property';
import { Alert, Badge, Card, EmptyState } from '../../../components/ui';
import { contractTypeLabel, propertyTypeLabel } from '../../../domain/property';
import { debtRatioLabel, riskGradeLabel, riskGradeToken } from '../../../domain/risk';
import { formatDate, formatWon } from '../../../lib/format';
import { propertyDetailPath } from '../../../lib/routes';
import { propertyQueries } from '../../../queries/property';
import styles from './RecentProperties.module.css';

/** 한 줄에 놓이는 수와 같다 — 레이아웃 맵 home 「구역 4 — 추천 매물 4열 그리드」 */
const RECENT_COUNT = 4;

/** 조건 없는 조회 — filter 를 주지 않았을 때. 모듈 상수로 두어 쿼리 키가 렌더마다 바뀌지 않게 한다 */
const NO_FILTER: PropertyFilter = {};

interface RecentPropertiesProps {
  /**
   * 목록 조회의 공통 필터(명세 1.1). 메인의 칩 줄이 고른 조건이다 — 지도 화면의 필터와는 별개다.
   * **쓰는 쪽이 같은 조건에 같은 객체를 넘긴다**(모듈 상수). 객체가 렌더마다 새로 만들어져도 쿼리 키는 값으로
   * 비교되어 요청이 늘지는 않지만, 조건이 바뀐 것과 아닌 것이 코드에서 갈려 보이게 한다.
   */
  filter?: PropertyFilter;
}

/**
 * 최근 등록 매물 (PROP-01). 데이터를 부르는 컴포넌트다. 조건은 filter prop 하나로 받는다.
 *
 * 목록 조회의 기본 정렬이 등록일 최신순이라(명세 1.3 · domain/property.ts
 * PROPERTY_SORT_DEFAULT_LABEL) 정렬 값을 보내지 않고 첫 쪽의 앞 네 건만 보여준다 —
 * 「최근 등록」 전용 엔드포인트를 만들지 않고 기존 쿼리 정의를 그대로 쓴다.
 *
 * 썸네일은 만들지 않는다(이미지 데이터가 없다). 그러면 참고 사이트 카드의 시각적 경계가 사라지므로
 * {components.card} 로 감싼다 (레이아웃 맵 구역 4 주석). 카드 안 위계는 PropertyList 와 같다 —
 * 가격 + 등급 배지 → 유형 → 주소 → 스펙.
 *
 * 등급 · 전세가율은 서버 값을 표시만 한다. 미분석(null) 문구는 domain/risk.ts가 갖는다.
 */
export function RecentProperties({ filter = NO_FILTER }: RecentPropertiesProps) {
  const listQuery = useInfiniteQuery(propertyQueries.list(filter));

  // 첫 쪽에서 앞 네 건만 쓴다 — 다음 쪽을 잇지 않는다. 더 찾는 길은 상단 내비게이션의 지도다(이슈 487)
  const items = useMemo(
    () => (listQuery.data?.pages[0]?.items ?? []).slice(0, RECENT_COUNT),
    [listQuery.data],
  );

  // 로딩 표시(스피너 · 스켈레톤)는 정의서 어휘에 없다 — 기존 목록 화면과 같은 한 줄 안내다
  if (listQuery.isPending) {
    return <p className="type-body">불러오는 중입니다.</p>;
  }

  if (listQuery.error) {
    return <Alert variant="error">{listQuery.error.message}</Alert>;
  }

  // 빈 목록은 조회 실패가 아니다 — {components.empty-state} 2줄 중앙 정렬
  if (items.length === 0) {
    return <EmptyState title="아직 등록된 매물이 없습니다." description="지도에서 자치구를 골라 찾아보세요." />;
  }

  return (
    <ul className={styles.grid}>
      {items.map((item) => (
        <li key={item.propertyId}>
          {/*
            카드 전체가 그 매물의 상세로 가는 링크다 — 카드 안에 다른 동작이 없어 통째로 잇는다
            (이슈 104. 목록 카드 꼴인데 눌러도 아무 일이 없던 것을 고친다). 이동이므로 라우터
            Link이고, 경로 조립은 lib/routes.ts 하나가 갖는다. 이름은 카드 글자 전부 대신 주소로
            둔다 — 「상세 보기」만으로는 카드마다 같은 이름이 된다 (PropertyList와 같은 방식).
          */}
          <Link
            to={propertyDetailPath(item.propertyId)}
            className={styles.cardLink}
            aria-label={`${item.address} 상세 보기`}
          >
            <Card className={styles.card}>
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
                {item.areaSqm}㎡ · {item.floor}층 · 전세가율 {debtRatioLabel(item.debtRatio)} ·{' '}
                {formatDate(item.registeredAt)} 등록
              </p>
            </Card>
          </Link>
        </li>
      ))}
    </ul>
  );
}
