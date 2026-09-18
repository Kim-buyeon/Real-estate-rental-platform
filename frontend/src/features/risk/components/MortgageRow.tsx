import { memo } from 'react';
import type { MortgageRecord } from '../../../api/risk';
import { kvRowClassName } from '../../../components/ui';
import { formatDate, formatWon } from '../../../lib/format';
// 행 레이아웃은 목록(RegistryTimeline)의 것이다 — OwnershipRow와 같은 이유로 같은 스타일을 읽는다
import styles from './RegistryTimeline.module.css';

export interface MortgageRowProps {
  mortgage: MortgageRecord;
}

/**
 * 을구 근저당 한 줄 (RISK-07). 표현 컴포넌트다 — props만 받고 훅을 부르지 않는다.
 * 말소된 등기는 흐리게 두되 지우지 않는다 — 이력이 이 화면의 내용이다.
 *
 * 행 모양은 {components.kv-row}다. `<ol>` · `<li>` 목록이라 `KvRow` 대신 `kvRowClassName`을
 * 입힌다 — 라벨 열 150에 채권최고액과 순위번호, 값 열에 근저당권자와 접수일이다.
 */
export const MortgageRow = memo(function MortgageRow({ mortgage }: MortgageRowProps) {
  const rowClasses = mortgage.isActive
    ? `${kvRowClassName} ${styles.row}`
    : `${kvRowClassName} ${styles.row} ${styles.inactive}`;

  return (
    <li className={rowClasses}>
      <span className={styles.label}>
        <span className="type-body-strong">{formatWon(mortgage.maxClaimAmount)}</span>
        <span className={`${styles.rank} type-body-sm`}>순위 {mortgage.rankNo}</span>
      </span>
      <span className={styles.value}>
        <span>
          {mortgage.creditor}
          {!mortgage.isActive && ' · 말소'}
        </span>
        <span className={`${styles.date} type-body-sm`}>{formatDate(mortgage.receivedDate)}</span>
      </span>
    </li>
  );
});
