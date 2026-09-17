import { memo } from 'react';
import type { RiskConsistency } from '../../../api/risk';
import { Card } from '../../../components/ui';
import styles from './ConsistencyCheck.module.css';

export interface ConsistencyCheckProps {
  consistency: RiskConsistency;
}

/** 표시 순서는 위험도 API 명세 1.1 consistency 설명의 순서 — 명의 일치 · 주소 일치 · 위반건축물 · 면적 대조 */
const ITEMS = [
  { key: 'ownerNameMatched', term: '명의 일치', met: '일치', unmet: '불일치' },
  { key: 'addressMatched', term: '주소 일치', met: '일치', unmet: '불일치' },
  { key: 'violationBuilding', term: '위반건축물', met: '해당 없음', unmet: '해당' },
  { key: 'areaMatched', term: '면적 대조', met: '일치', unmet: '불일치' },
] as const;

/**
 * 명의 · 문서 정합 확인 (RISK-04). 서버가 준 대조 결과를 표시만 한다.
 * violationBuilding은 참일 때가 문제이므로 다른 셋과 반대로 읽는다.
 */
export const ConsistencyCheck = memo(function ConsistencyCheck({ consistency }: ConsistencyCheckProps) {
  return (
    <Card className={styles.card}>
      <h3 className={`${styles.title} type-body-strong`}>명의 · 문서 정합</h3>

      <dl className={`${styles.facts} type-caption`}>
        {ITEMS.map((item) => {
          const isMet = item.key === 'violationBuilding' ? !consistency[item.key] : consistency[item.key];
          return (
            <div key={item.key} className={styles.fact}>
              <dt>{item.term}</dt>
              <dd className={isMet ? undefined : styles.flagged}>{isMet ? item.met : item.unmet}</dd>
            </div>
          );
        })}
      </dl>
    </Card>
  );
});
