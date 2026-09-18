import { memo } from 'react';
import { Alert } from '../../../components/ui';
import { ownershipRightTypeLabel, type OwnershipRightType } from '../../../domain/risk';
import styles from './RiskFindings.module.css';

export interface RiskFindingsProps {
  rightViolations: OwnershipRightType[];
  warnings: OwnershipRightType[];
}

/**
 * 등기 검출 항목 (RISK-03). 판정에 반영된 권리 침해와 반영되지 않는 경고를 구분해 보여준다 —
 * 둘의 무게가 다르므로 한 목록으로 합치지 않는다 (위험도 API 명세 1.1 rightViolations · warnings).
 *
 * 상세 패널의 한 섹션이다 — 좌우 패딩과 섹션 사이 띠는 패널이 갖는다.
 */
export const RiskFindings = memo(function RiskFindings({ rightViolations, warnings }: RiskFindingsProps) {
  return (
    <section aria-label="등기 검출 항목">
      <h3 className={`${styles.title} type-heading-3`}>등기 검출 항목</h3>

      <section className={styles.section}>
        <h4 className={`${styles.sectionTitle} type-label`}>권리 침해</h4>
        <p className={`${styles.note} type-caption`}>등급 판정에 반영된 항목입니다.</p>
        {rightViolations.length === 0 ? (
          <p className={`${styles.empty} type-caption`}>없음</p>
        ) : (
          <Alert variant="error" className={styles.findings}>
            <ul className={styles.list}>
              {rightViolations.map((violation) => (
                <li key={violation}>{ownershipRightTypeLabel(violation)}</li>
              ))}
            </ul>
          </Alert>
        )}
      </section>

      <section className={styles.section}>
        <h4 className={`${styles.sectionTitle} type-label`}>경고</h4>
        <p className={`${styles.note} type-caption`}>등급 판정에 반영되지 않은 참고 항목입니다.</p>
        {warnings.length === 0 ? (
          <p className={`${styles.empty} type-caption`}>없음</p>
        ) : (
          <Alert variant="info" className={styles.findings}>
            <ul className={styles.list}>
              {warnings.map((warning) => (
                <li key={warning}>{ownershipRightTypeLabel(warning)}</li>
              ))}
            </ul>
          </Alert>
        )}
      </section>
    </section>
  );
});
