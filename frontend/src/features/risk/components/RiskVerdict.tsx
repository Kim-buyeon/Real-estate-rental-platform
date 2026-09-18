import { memo } from 'react';
import type { RiskAnalysis } from '../../../api/risk';
import { Badge, KvRow, KvRowList } from '../../../components/ui';
import { gradeReasonLabel, priceTypeLabel, riskGradeLabel, riskGradeToken } from '../../../domain/risk';
import { formatDate, formatDateTime, formatPercent, formatWon } from '../../../lib/format';
import styles from './RiskVerdict.module.css';

export interface RiskVerdictProps {
  analysis: RiskAnalysis;
}

/**
 * 위험 등급과 판정 근거 (RISK-01 · 02). 서버가 준 값을 표시만 한다 — 화면에서 다시 판정하지 않고
 * 임계 수치도 적지 않는다 (frontend/CLAUDE.md 상태). 등급 색은 domain/risk.ts의 토큰 매핑을 거친다.
 *
 * 상세 패널의 한 섹션이다 — 좌우 패딩과 섹션 사이 띠는 패널이 갖는다 (PropertyDetailPanel.module.css).
 */
export const RiskVerdict = memo(function RiskVerdict({ analysis }: RiskVerdictProps) {
  return (
    <section aria-label="위험 등급">
      <div className={styles.header}>
        <Badge variant={riskGradeToken(analysis.riskGrade)}>{riskGradeLabel(analysis.riskGrade)}</Badge>
        <p className={`${styles.reason} type-body-strong`}>{gradeReasonLabel(analysis.gradeReason)}</p>
      </div>

      <KvRowList>
        <KvRow label="전세가율">{formatPercent(analysis.debtRatio)}</KvRow>
        <KvRow label="적용 시세">
          {formatWon(analysis.marketPrice)}
          <span className={`${styles.source} type-body-sm`}>
            {priceTypeLabel(analysis.priceType)} · {formatDate(analysis.priceDate)} 기준
          </span>
        </KvRow>
        <KvRow label="선순위채권 합계">{formatWon(analysis.seniorDebtTotal)}</KvRow>
        <KvRow label="깡통전세">
          {analysis.isNegativeEquity ? '해당' : '해당 없음'}
        </KvRow>
      </KvRowList>

      <p className={`${styles.analyzedAt} type-body-sm`}>분석 기준 {formatDateTime(analysis.analyzedAt)}</p>
    </section>
  );
});
