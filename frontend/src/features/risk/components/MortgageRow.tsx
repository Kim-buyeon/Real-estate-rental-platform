import { memo } from 'react';
import type { MortgageRecord } from '../../../api/risk';
import { formatDate, formatWon } from '../../../lib/format';
// 행 레이아웃은 목록(RegistryTimeline)의 것이다 — OwnershipRow와 같은 이유로 같은 스타일을 읽는다
import styles from './RegistryTimeline.module.css';

export interface MortgageRowProps {
  mortgage: MortgageRecord;
}

/**
 * 을구 근저당 한 줄 (RISK-07). 표현 컴포넌트다 — props만 받고 훅을 부르지 않는다.
 * 말소된 등기는 흐리게 두되 지우지 않는다 — 이력이 이 화면의 내용이다.
 */
export const MortgageRow = memo(function MortgageRow({ mortgage }: MortgageRowProps) {
  return (
    <li className={mortgage.isActive ? styles.row : `${styles.row} ${styles.inactive}`}>
      <span className={styles.rank}>{mortgage.rankNo}</span>
      <span className={styles.main}>
        <span className="type-body-strong">{formatWon(mortgage.maxClaimAmount)}</span>
        <span className={styles.sub}>
          {mortgage.creditor}
          {!mortgage.isActive && ' · 말소'}
        </span>
      </span>
      <span className={styles.date}>{formatDate(mortgage.receivedDate)}</span>
    </li>
  );
});
