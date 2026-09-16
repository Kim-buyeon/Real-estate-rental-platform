import { memo } from 'react';
import type { DistrictCount } from '../../../api/property';
import { RISK_GRADES, RISK_GRADE_LABEL, riskGradeToken } from '../../../domain/risk';
import { formatCount } from '../../../lib/format';
import styles from './DistrictOverlayContent.module.css';

/** 토큰 이름 → 이 컴포넌트의 CSS 클래스. 등급 → 토큰은 domain/risk.ts가 갖는다 */
const CLASS_BY_TOKEN: Record<ReturnType<typeof riskGradeToken>, string | undefined> = {
  'risk-safe': styles.riskSafe,
  'risk-caution': styles.riskCaution,
  'risk-danger': styles.riskDanger,
  // 미분석은 등급 분포에 오지 않는다 — 명세 1.5의 gradeCounts는 등급 세 개다
  'risk-unanalyzed': undefined,
};

interface DistrictOverlayContentProps {
  district: DistrictCount;
  onSelect: (name: string) => void;
}

/** 1단계 자치구 오버레이 — 건수와 등급 분포. 개별 마커는 이 단계에서 그리지 않는다 (매물 API 명세 1.2) */
export const DistrictOverlayContent = memo(function DistrictOverlayContent({
  district,
  onSelect,
}: DistrictOverlayContentProps) {
  const distribution = RISK_GRADES.map((grade) => ({
    grade,
    count: district.gradeCounts[grade] ?? 0,
    className: CLASS_BY_TOKEN[riskGradeToken(grade)],
  }));

  return (
    <button
      type="button"
      className={styles.overlay}
      onClick={() => onSelect(district.name)}
      aria-label={`${district.name} 매물 ${formatCount(district.count)}건 — ${distribution
        .map(({ grade, count }) => `${RISK_GRADE_LABEL[grade]} ${formatCount(count)}건`)
        .join(' · ')}`}
    >
      <span className={`${styles.name} type-label`}>{district.name}</span>
      <span className={`${styles.count} type-heading-3`}>{formatCount(district.count)}</span>
      <span className={styles.grades} aria-hidden="true">
        {distribution.map(({ grade, count, className }) => (
          <span key={grade} className={className}>
            {formatCount(count)}
          </span>
        ))}
      </span>
    </button>
  );
});
