import { memo } from 'react';
import type { DistrictCount } from '../../../api/property';
import styles from './DistrictOverlayContent.module.css';

interface DistrictOverlayContentProps {
  district: DistrictCount;
  onSelect: (name: string) => void;
}

/** 1단계 자치구 오버레이 — 건수와 등급 분포. 개별 마커는 이 단계에서 그리지 않는다 (매물 API 명세 1.2) */
export const DistrictOverlayContent = memo(function DistrictOverlayContent({
  district,
  onSelect,
}: DistrictOverlayContentProps) {
  const { SAFE = 0, CAUTION = 0, DANGER = 0 } = district.gradeCounts;

  return (
    <button
      type="button"
      className={styles.overlay}
      onClick={() => onSelect(district.name)}
      aria-label={`${district.name} 매물 ${district.count}건`}
    >
      <span className={`${styles.name} type-label`}>{district.name}</span>
      <span className={`${styles.count} type-heading-3`}>{district.count.toLocaleString('ko-KR')}</span>
      <span className={styles.grades} aria-hidden="true">
        <span className={styles.safe}>{SAFE}</span>
        <span className={styles.caution}>{CAUTION}</span>
        <span className={styles.danger}>{DANGER}</span>
      </span>
    </button>
  );
});
