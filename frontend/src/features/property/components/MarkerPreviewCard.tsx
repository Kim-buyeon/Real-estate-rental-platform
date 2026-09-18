import { memo } from 'react';
import type { PropertyMarker } from '../../../api/property';
import { Badge, Button, Card } from '../../../components/ui';
import { contractTypeLabel } from '../../../domain/property';
import { debtRatioLabel, riskGradeLabel, riskGradeToken } from '../../../domain/risk';
import { formatWon } from '../../../lib/format';
import styles from './MarkerPreviewCard.module.css';

interface MarkerPreviewCardProps {
  marker: PropertyMarker;
  onClose: () => void;
  onOpenDetail: (propertyId: number) => void;
}

/**
 * 마커 미리보기 — 마커 응답에 담긴 값만 쓴다. 추가 호출을 하지 않는다 (매물 API 명세 1.4).
 * 상세는 별도 화면이 아니라 지도 옆 패널이다 — 지도 위치 · 확대 수준 · 필터를 유지한다.
 */
export const MarkerPreviewCard = memo(function MarkerPreviewCard({
  marker,
  onClose,
  onOpenDetail,
}: MarkerPreviewCardProps) {
  return (
    <Card className={styles.card}>
      <div className={styles.header}>
        <Badge variant={riskGradeToken(marker.riskGrade)}>{riskGradeLabel(marker.riskGrade)}</Badge>
        <Button type="button" variant="ghost" size="sm" onClick={onClose} aria-label="미리보기 닫기">
          ✕
        </Button>
      </div>

      <p className={`${styles.deposit} type-heading-3`}>{formatWon(marker.deposit)}</p>

      <dl className={`${styles.facts} type-caption`}>
        <div className={styles.fact}>
          <dt>계약유형</dt>
          <dd>{contractTypeLabel(marker.contractType)}</dd>
        </div>
        {marker.monthlyRent > 0 && (
          <div className={styles.fact}>
            <dt>월세</dt>
            <dd>{formatWon(marker.monthlyRent)}</dd>
          </div>
        )}
        <div className={styles.fact}>
          <dt>자치구</dt>
          <dd>{marker.district}</dd>
        </div>
        <div className={styles.fact}>
          <dt>전세가율</dt>
          <dd>{debtRatioLabel(marker.debtRatio)}</dd>
        </div>
        <div className={styles.fact}>
          <dt>선순위채권</dt>
          <dd>{marker.hasSeniorDebt ? '있음' : '없음'}</dd>
        </div>
      </dl>

      <Button type="button" size="sm" className={styles.detail} onClick={() => onOpenDetail(marker.propertyId)}>
        상세 보기
      </Button>
    </Card>
  );
});
