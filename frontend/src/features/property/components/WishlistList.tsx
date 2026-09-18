import { useInfiniteQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { Link } from 'react-router';
import { Alert, Badge, Button, Card, EmptyState, buttonClassName } from '../../../components/ui';
import { riskGradeLabel, riskGradeToken } from '../../../domain/risk';
import { formatDate, formatWon } from '../../../lib/format';
import { propertyDetailPath } from '../../../lib/routes';
import { useRemoveWishlist, wishlistQueries } from '../../../queries/property';
import styles from './WishlistList.module.css';

/**
 * 관심 매물 목록 (PROP-05). 데이터를 부르는 컴포넌트다.
 *
 * 커서 목록이라 「더 보기」로 다음 쪽을 잇는다 — 전체 건수는 주지 않는다 (공통 규약 1.4).
 * 서버가 등록 역순으로 주므로 화면에서 다시 정렬하지 않는다 (명세 1.9).
 *
 * 한 줄에 현재 등급과 직전 등급을 함께 둔다 — 「내 관심 매물의 등급이 어느 방향으로 움직였나」가
 * 이 화면의 쓰임이다. 둘 다 null일 수 있고(미분석 · 첫 분석) 그 문구는 domain/risk.ts가 갖는다.
 *
 * 항목마다 해제 버튼을 둔다 — 관심 목록에서 빼는 것이 이 화면의 동작이고, 없으면 지도로 가서 그
 * 매물을 다시 찾아 패널을 열어야 한다 (PROP-05 「등록 · 해제하고 목록을 조회한다」).
 * 비로그인 분기는 없다 — 이 화면은 RequireAuth 아래다. 확인 절차도 두지 않는다.
 */
export function WishlistList() {
  const wishlistQuery = useInfiniteQuery(wishlistQueries.list());
  // 훅 하나를 목록 전체가 나눠 쓴다 — 지금 처리 중인 것이 어느 항목인지는 변수(propertyId)로 안다.
  // 해제가 성공하면 훅의 무효화가 이 목록을 다시 불러 그 항목이 사라진다 (queries/property.ts)
  const removeMutation = useRemoveWishlist();

  // 쪽마다 나뉜 항목을 한 배열로 잇는다. 렌더마다 다시 만들지 않는다
  const items = useMemo(
    () => wishlistQuery.data?.pages.flatMap((page) => page.items) ?? [],
    [wishlistQuery.data],
  );

  if (wishlistQuery.isPending) {
    return <p className="type-body">불러오는 중입니다.</p>;
  }

  if (wishlistQuery.error) {
    return <Alert variant="error">{wishlistQuery.error.message}</Alert>;
  }

  // 빈 목록은 조회 실패가 아니다 - {components.empty-state} 2줄 중앙 정렬로 알림 목록과 같은 모양이다
  if (items.length === 0) {
    return <EmptyState title="등록한 관심 매물이 없습니다." description="지도에서 매물을 열어 등록해 보세요." />;
  }

  return (
    <>
      <ul className={styles.list}>
        {items.map((item) => (
          <li key={item.propertyId}>
            <Card className={styles.item}>
              <div className={styles.head}>
                <span className="type-body-strong">{item.district}</span>
                <span className={`${styles.added} type-caption`}>{formatDate(item.addedAt)} 등록</span>
              </div>

              <dl className={`${styles.facts} type-caption`}>
                <div className={styles.fact}>
                  <dt>보증금</dt>
                  <dd>{formatWon(item.deposit)}</dd>
                </div>
                <div className={styles.fact}>
                  <dt>직전 등급</dt>
                  <dd>{riskGradeLabel(item.previousGrade)}</dd>
                </div>
                <div className={styles.fact}>
                  <dt>현재 등급</dt>
                  <dd>
                    <Badge variant={riskGradeToken(item.riskGrade)}>{riskGradeLabel(item.riskGrade)}</Badge>
                  </dd>
                </div>
              </dl>

              {/* 실패 문구는 서버 error.message 그대로다. 실패한 항목에만 붙인다 */}
              {removeMutation.error && removeMutation.variables === item.propertyId && (
                <Alert variant="error">{removeMutation.error.message}</Alert>
              )}

              <div className={styles.actions}>
                {/*
                  항목에서 그 매물의 상세로 간다 (이슈 104). 상세가 화면이 아니라 지도 옆 패널이라
                  지도 경로 + 매물 번호이고, 조립은 lib/routes.ts 하나가 갖는다. 이동이라 라우터
                  Link이고 버튼처럼 보이는 것은 buttonClassName이다 — 두 번째 버튼 컴포넌트를
                  만들지 않는다. 카드를 통째로 링크로 만들지 않은 것은 안에 해제 동작이 있어서다.
                */}
                <Link to={propertyDetailPath(item.propertyId)} className={buttonClassName('secondary', 'sm')}>
                  상세 보기
                </Link>
                <Button
                  type="button"
                  size="sm"
                  variant="secondary"
                  onClick={() => removeMutation.mutate(item.propertyId)}
                  isLoading={removeMutation.isPending && removeMutation.variables === item.propertyId}
                >
                  관심 해제
                </Button>
              </div>
            </Card>
          </li>
        ))}
      </ul>

      {wishlistQuery.hasNextPage && (
        <Button
          type="button"
          variant="secondary"
          onClick={() => void wishlistQuery.fetchNextPage()}
          isLoading={wishlistQuery.isFetchingNextPage}
        >
          더 보기
        </Button>
      )}
    </>
  );
}
