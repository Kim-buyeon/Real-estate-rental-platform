import { memo } from 'react';
import { RISK_GRADES, RISK_GRADE_LABEL, riskGradeToken } from '../../../domain/risk';
import { formatCount } from '../../../lib/format';
import type { MarkerCluster } from '../map';
import styles from './MarkerClusterContent.module.css';

/** 토큰 이름 → 이 컴포넌트의 CSS 클래스. 등급 → 토큰은 domain/risk.ts가 갖는다 */
const CLASS_BY_TOKEN: Record<ReturnType<typeof riskGradeToken>, string | undefined> = {
  'risk-safe': styles.riskSafe,
  'risk-caution': styles.riskCaution,
  'risk-danger': styles.riskDanger,
  'risk-unanalyzed': styles.riskUnanalyzed,
};

interface MarkerClusterContentProps {
  cluster: MarkerCluster;
  onSelect: (cluster: MarkerCluster) => void;
}

/**
 * 겹친 매물 묶음. 같은 건물의 매물은 좌표가 같아 포개지므로 건수로 보이고,
 * 누르면 그 셀로 확대해 들어간다 (kakao-map 7장 — 클라이언트 격자 묶음으로 정했다).
 */
export const MarkerClusterContent = memo(function MarkerClusterContent({
  cluster,
  onSelect,
}: MarkerClusterContentProps) {
  const distribution = RISK_GRADES.map((grade) => ({
    grade,
    count: cluster.gradeCounts[grade],
    className: CLASS_BY_TOKEN[riskGradeToken(grade)],
  })).filter(({ count }) => count > 0);

  const hasUnanalyzed = cluster.gradeCounts.unanalyzed > 0;

  return (
    <button
      type="button"
      className={styles.cluster}
      onClick={() => onSelect(cluster)}
      aria-label={`매물 ${formatCount(cluster.count)}건 묶음. 눌러서 확대`}
    >
      <span className={`${styles.count} type-label`}>{formatCount(cluster.count)}</span>
      <span className={styles.distribution} aria-hidden="true">
        {distribution.map(({ grade, className }) => (
          <span key={grade} className={`${styles.dot} ${className ?? ''}`} title={RISK_GRADE_LABEL[grade]} />
        ))}
        {hasUnanalyzed && <span className={`${styles.dot} ${CLASS_BY_TOKEN['risk-unanalyzed'] ?? ''}`} />}
      </span>
    </button>
  );
});
