import { memo } from 'react';
import type { PropertyMarker } from '../../../api/property';
import { riskGradeToken } from '../../../domain/risk';
import { formatDepositShort } from '../../../lib/format';
import styles from './PropertyMarkerContent.module.css';

/** 토큰 이름 → 이 컴포넌트의 CSS 클래스. 등급 → 토큰은 domain/risk.ts가 갖는다 (components/ui/Badge.tsx와 같은 방식) */
const CLASS_BY_TOKEN: Record<string, string | undefined> = {
  'risk-safe': styles.riskSafe,
  'risk-caution': styles.riskCaution,
  'risk-danger': styles.riskDanger,
  'risk-unanalyzed': styles.riskUnanalyzed,
};

interface PropertyMarkerContentProps {
  marker: PropertyMarker;
  isSelected: boolean;
  onSelect: (propertyId: number) => void;
  onHover: (propertyId: number) => void;
}

/** 2단계 매물 마커 — 보증금 축약과 등급 색 (매물 API 명세 1.4). 포인터가 없으면 클릭이 카드 표시다 */
export const PropertyMarkerContent = memo(function PropertyMarkerContent({
  marker,
  isSelected,
  onSelect,
  onHover,
}: PropertyMarkerContentProps) {
  const classes = [
    styles.marker,
    CLASS_BY_TOKEN[riskGradeToken(marker.riskGrade)],
    isSelected ? styles.selected : undefined,
    'type-caption',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <button
      type="button"
      className={classes}
      onClick={() => onSelect(marker.propertyId)}
      onMouseEnter={() => onHover(marker.propertyId)}
      onFocus={() => onHover(marker.propertyId)}
    >
      {formatDepositShort(marker.deposit)}
    </button>
  );
});
