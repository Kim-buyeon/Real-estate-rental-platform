import { useQuery } from '@tanstack/react-query';
import { Alert } from '../../../components/ui';
import { applicabilityLabel } from '../../../domain/property';
import { formatDate, formatDateTime } from '../../../lib/format';
import { propertyQueries } from '../../../queries/property';
import styles from './BuildingLedgerSection.module.css';

interface BuildingLedgerSectionProps {
  propertyId: number;
}

/**
 * 건축물대장 (PROP-04). 국토부 수집 데이터를 표시만 한다.
 *
 * 데이터를 부르는 컴포넌트다 — 부모가 이 컴포넌트를 펼칠 때 비로소 마운트하므로 여기서 쿼리를
 * 부르는 것이 곧 「펼칠 때 조회」다 (매물 API 명세 1.4 「탐색 동작과 호출 시점」).
 *
 * 위반건축물은 참일 때가 문제이므로 다른 항목과 반대로 읽는다 — 보증보험 집 단위 조건에 걸린다(RISK-05).
 */
export function BuildingLedgerSection({ propertyId }: BuildingLedgerSectionProps) {
  const ledgerQuery = useQuery(propertyQueries.ledger(propertyId));

  if (ledgerQuery.isPending) {
    return <p className="type-caption">불러오는 중입니다.</p>;
  }

  if (ledgerQuery.error) {
    return <Alert variant="error">{ledgerQuery.error.message}</Alert>;
  }

  const ledger = ledgerQuery.data;

  return (
    <>
      <dl className={`${styles.facts} type-caption`}>
        <div className={styles.fact}>
          <dt>주용도</dt>
          <dd>{ledger.mainPurpose}</dd>
        </div>
        <div className={styles.fact}>
          <dt>주거용</dt>
          {/* 주거용은 거짓일 때가 문제다 — 위반건축물과 강조 조건이 반대다. 문구는 domain이 갖는다 */}
          <dd className={ledger.isResidential ? undefined : styles.flagged}>
            {applicabilityLabel(ledger.isResidential)}
          </dd>
        </div>
        <div className={styles.fact}>
          <dt>위반건축물</dt>
          <dd className={ledger.violationBuilding ? styles.flagged : undefined}>
            {applicabilityLabel(ledger.violationBuilding)}
          </dd>
        </div>
        <div className={styles.fact}>
          <dt>연면적</dt>
          <dd>{ledger.totalFloorArea}㎡</dd>
        </div>
        <div className={styles.fact}>
          <dt>전용면적</dt>
          <dd>{ledger.exclusiveArea}㎡</dd>
        </div>
        <div className={styles.fact}>
          <dt>사용승인일</dt>
          <dd>{formatDate(ledger.approvalDate)}</dd>
        </div>
      </dl>

      <p className={`${styles.collectedAt} type-caption`}>대장 수집 {formatDateTime(ledger.collectedAt)}</p>
    </>
  );
}
