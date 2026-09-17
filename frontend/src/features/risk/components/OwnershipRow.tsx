import { memo } from 'react';
import type { OwnershipRecord } from '../../../api/risk';
import { ownershipRightTypeLabel } from '../../../domain/risk';
import { formatDate } from '../../../lib/format';
// 행 레이아웃은 목록(RegistryTimeline)의 것이다 — 갑구 · 을구 두 표의 순위번호 열과 날짜 열이 같은
// 폭으로 맞아야 해서 두 행 컴포넌트가 목록의 스타일을 함께 읽는다. 두 파일로 나누면 값이 갈라진다
import styles from './RegistryTimeline.module.css';

export interface OwnershipRowProps {
  ownership: OwnershipRecord;
}

/**
 * 갑구 소유권 변동 한 줄 (RISK-07). 표현 컴포넌트다 — props만 받고 훅을 부르지 않는다.
 * 말소된 등기는 흐리게 두되 지우지 않는다 — 이력이 이 화면의 내용이다.
 */
export const OwnershipRow = memo(function OwnershipRow({ ownership }: OwnershipRowProps) {
  return (
    <li className={ownership.isActive ? styles.row : `${styles.row} ${styles.inactive}`}>
      <span className={styles.rank}>{ownership.rankNo}</span>
      <span className={styles.main}>
        <span className="type-body-strong">{ownershipRightTypeLabel(ownership.rightType)}</span>
        <span className={styles.sub}>
          {ownership.holderName} · {ownership.cause}
          {!ownership.isActive && ' · 말소'}
        </span>
      </span>
      <span className={styles.date}>{formatDate(ownership.receivedDate)}</span>
    </li>
  );
});
