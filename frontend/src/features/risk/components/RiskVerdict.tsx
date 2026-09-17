import { memo } from 'react';
import type { RiskAnalysis } from '../../../api/risk';
import { Badge, Card } from '../../../components/ui';
import { gradeReasonLabel, priceTypeLabel, riskGradeLabel, riskGradeToken } from '../../../domain/risk';
import { formatDate, formatDateTime, formatPercent, formatWon } from '../../../lib/format';
import styles from './RiskVerdict.module.css';

export interface RiskVerdictProps {
  analysis: RiskAnalysis;
}

/**
 * 위험 등급과 판정 근거 (RISK-01 · 02). 서버가 준 값을 표시만 한다 — 화면에서 다시 판정하지 않고
 * 임계 수치도 적지 않는다 (frontend/CLAUDE.md 상태). 등급 색은 domain/risk.ts의 토큰 매핑을 거친다.
 */
export const RiskVerdict = memo(function RiskVerdict({ analysis }: RiskVerdictProps) {
  return (
    <Card className={styles.card}>
      <div className={styles.header}>
        <Badge variant={riskGradeToken(analysis.riskGrade)}>{riskGradeLabel(analysis.riskGrade)}</Badge>
        <p className={`${styles.reason} type-body-strong`}>
          {gradeReasonLabel(analysis.gradeReason)}
        </p>
      </div>

      <dl className={`${styles.facts} type-caption`}>
        <div className={styles.fact}>
          <dt>전세가율</dt>
          <dd>{formatPercent(analysis.debtRatio)}</dd>
        </div>
        <div className={styles.fact}>
          <dt>적용 시세</dt>
          <dd>
            {formatWon(analysis.marketPrice)}
            <span className={styles.source}>
              {priceTypeLabel(analysis.priceType)} · {formatDate(analysis.priceDate)} 기준
            </span>
          </dd>
        </div>
        <div className={styles.fact}>
          <dt>선순위채권 합계</dt>
          <dd>{formatWon(analysis.seniorDebtTotal)}</dd>
        </div>
        <div className={styles.fact}>
          <dt>깡통전세</dt>
          <dd className={analysis.isNegativeEquity ? styles.flagged : undefined}>
            {analysis.isNegativeEquity ? '해당' : '해당 없음'}
          </dd>
        </div>
      </dl>

      <p className={`${styles.analyzedAt} type-caption`}>분석 기준 {formatDateTime(analysis.analyzedAt)}</p>
    </Card>
  );
});
