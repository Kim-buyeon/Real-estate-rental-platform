import { memo } from 'react';
import type { PropertyMarker } from '../../../api/property';
import { formatDepositShort } from '../../../lib/format';
import styles from './PropertyMarkerContent.module.css';

const CLASS_BY_GRADE = {
  SAFE: styles.safe,
  CAUTION: styles.caution,
  DANGER: styles.danger,
} as const;

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
  const gradeClass = marker.riskGrade ? CLASS_BY_GRADE[marker.riskGrade] : styles.unanalyzed;
  const classes = [styles.marker, gradeClass, isSelected ? styles.selected : undefined, 'type-caption']
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
