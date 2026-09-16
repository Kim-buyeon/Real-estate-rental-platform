import { memo } from 'react';
import type { PropertyMarker } from '../../../api/property';
import { Badge, Button, Card } from '../../../components/ui';
import { CONTRACT_TYPE_LABEL } from '../../../domain/property';
import { UNANALYZED_LABEL, riskGradeLabel, riskGradeToken } from '../../../domain/risk';
import { formatPercent, formatWon } from '../../../lib/format';
import styles from './MarkerPreviewCard.module.css';

interface MarkerPreviewCardProps {
  marker: PropertyMarker;
  onClose: () => void;
}

/**
 * 마커 미리보기 — 마커 응답에 담긴 값만 쓴다. 추가 호출을 하지 않는다 (매물 API 명세 1.4).
 * 「상세 보기」는 지도 옆 상세 패널 슬라이스에서 이 카드에 붙는다.
 */
export const MarkerPreviewCard = memo(function MarkerPreviewCard({ marker, onClose }: MarkerPreviewCardProps) {
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
          <dd>{CONTRACT_TYPE_LABEL[marker.contractType]}</dd>
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
          <dd>{marker.debtRatio === null ? UNANALYZED_LABEL : formatPercent(marker.debtRatio)}</dd>
        </div>
        <div className={styles.fact}>
          <dt>선순위채권</dt>
          <dd>{marker.hasSeniorDebt ? '있음' : '없음'}</dd>
        </div>
      </dl>
    </Card>
  );
});
