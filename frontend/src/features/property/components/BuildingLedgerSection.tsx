import { useQuery } from '@tanstack/react-query';
import { Alert, KvRow, KvRowList } from '../../../components/ui';
import {
  applicabilityLabel,
  areaLabel,
  isLedgerMissing,
  LEDGER_MISSING_NOTICE,
  ledgerValueLabel,
  violationBuildingLabel,
} from '../../../domain/property';
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
 * 위반건축물은 참일 때가 문제다 — 보증보험 집 단위 조건에 걸린다(RISK-05). 그 사실을 값의 색으로
 * 강조하지는 않는다 (BuildingLedgerSection.module.css).
 *
 * 값이 null이면 「확인 불가」다 — 해당 없음 · 0으로 보이지 않게 문구는 domain/property.ts의 함수가 만든다.
 * 대장을 찾지 못하면(propertyId 밖 전부 null, 명세 1.8) 행을 그리지 않고 안내 하나만 둔다.
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

  if (isLedgerMissing(ledger)) {
    return <Alert variant="info">{LEDGER_MISSING_NOTICE}</Alert>;
  }

  return (
    <>
      <KvRowList>
        <KvRow label="주용도">{ledgerValueLabel(ledger.mainPurpose, String)}</KvRow>
        {/* 문구는 domain이 갖는다 — 주거용은 거짓일 때, 위반건축물은 참일 때가 문제다 */}
        <KvRow label="주거용">{applicabilityLabel(ledger.isResidential)}</KvRow>
        <KvRow label="위반건축물">{violationBuildingLabel(ledger.violationBuilding)}</KvRow>
        <KvRow label="연면적">{ledgerValueLabel(ledger.totalFloorArea, areaLabel)}</KvRow>
        <KvRow label="전용면적">{ledgerValueLabel(ledger.exclusiveArea, areaLabel)}</KvRow>
        <KvRow label="사용승인일">{ledgerValueLabel(ledger.approvalDate, formatDate)}</KvRow>
      </KvRowList>

      <p className={`${styles.collectedAt} type-body-sm`}>대장 수집 {ledgerValueLabel(ledger.collectedAt, formatDateTime)}</p>
    </>
  );
}
