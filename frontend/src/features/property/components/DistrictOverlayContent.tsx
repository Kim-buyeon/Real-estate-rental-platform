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
  onHover: (name: string) => void;
}

/**
 * 1단계 자치구 오버레이 — 건수와 등급 분포. 개별 마커는 이 단계에서 그리지 않는다 (매물 API 명세 1.2).
 *
 * 서울 전체에서 25개가 동시에 뜨고 도심 쪽은 중심이 가까워 서로 가린다. 한 줄로 좁게 그리고,
 * 가리킨 것은 앞으로 올라온다(zIndex는 MapExplorer가 준다).
 */
export const DistrictOverlayContent = memo(function DistrictOverlayContent({
  district,
  onSelect,
  onHover,
}: DistrictOverlayContentProps) {
  const distribution = RISK_GRADES.map((grade) => ({
    grade,
    count: district.gradeCounts[grade] ?? 0,
    className: CLASS_BY_TOKEN[riskGradeToken(grade)],
  }));

  return (
    <button
      type="button"
      className={`${styles.overlay} type-caption`}
      onClick={() => onSelect(district.name)}
      onMouseEnter={() => onHover(district.name)}
      onFocus={() => onHover(district.name)}
      aria-label={`${district.name} 매물 ${formatCount(district.count)}건 — ${distribution
        .map(({ grade, count }) => `${RISK_GRADE_LABEL[grade]} ${formatCount(count)}건`)
        .join(' · ')}`}
    >
      <span className={styles.name}>{district.name}</span>
      <span className={styles.count}>{formatCount(district.count)}</span>
      <span className={styles.grades} aria-hidden="true">
        {distribution
          .filter(({ count }) => count > 0)
          .map(({ grade, className }) => (
            <span key={grade} className={`${styles.dot} ${className ?? ''}`} />
          ))}
      </span>
    </button>
  );
});
