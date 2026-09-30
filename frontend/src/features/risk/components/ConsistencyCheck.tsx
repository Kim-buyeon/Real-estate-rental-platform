import { memo } from 'react';
import type { RiskConsistency } from '../../../api/risk';
import { KvRow, KvRowList } from '../../../components/ui';
import { consistencyMatchLabel, violationBuildingLabel } from '../../../domain/risk';
import styles from './ConsistencyCheck.module.css';

export interface ConsistencyCheckProps {
  consistency: RiskConsistency;
}

/**
 * 표시 순서는 위험도 API 명세 1.1 consistency 설명의 순서 — 명의 일치 · 주소 일치 · 위반건축물 · 면적 대조.
 * 값 → 문구는 domain/risk.ts의 함수가 한다. null(대장 확인 불가)도 거기서 「확인 불가」가 된다.
 */
const ITEMS = [
  { key: 'ownerNameMatched', term: '명의 일치', toLabel: consistencyMatchLabel },
  { key: 'addressMatched', term: '주소 일치', toLabel: consistencyMatchLabel },
  // 위반건축물은 다른 셋과 달리 일치 여부가 아니라 해당 여부다
  { key: 'violationBuilding', term: '위반건축물', toLabel: violationBuildingLabel },
  { key: 'areaMatched', term: '면적 대조', toLabel: consistencyMatchLabel },
] as const satisfies readonly {
  key: keyof RiskConsistency;
  term: string;
  toLabel: (value: boolean | null) => string;
}[];

/**
 * 명의 · 문서 정합 확인 (RISK-04). 서버가 준 대조 결과를 표시만 한다.
 * violationBuilding은 참일 때가 문제이므로 다른 셋과 반대로 읽는다. 대장에서 오는 셋은 null이면 「확인 불가」다.
 *
 * 상세 패널의 한 섹션이다 — 좌우 패딩과 섹션 사이 띠는 패널이 갖는다.
 */
export const ConsistencyCheck = memo(function ConsistencyCheck({ consistency }: ConsistencyCheckProps) {
  return (
    <section aria-label="명의 · 문서 정합">
      <h3 className={`${styles.title} type-heading-3`}>명의 · 문서 정합</h3>

      <KvRowList>
        {ITEMS.map((item) => (
          <KvRow key={item.key} label={item.term}>
            {item.toLabel(consistency[item.key])}
          </KvRow>
        ))}
      </KvRowList>
    </section>
  );
});
