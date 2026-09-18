import { memo } from 'react';
import { Alert } from '../../../components/ui';
import { personalConditionLabel, type PersonalCondition } from '../../../domain/risk';
import styles from './PersonalConditions.module.css';

export interface PersonalConditionsProps {
  conditions: PersonalCondition[];
}

/**
 * 개인 자격 확인 사항 (RISK-05). 시스템이 판정하지 않는 항목이므로 판정 결과와 섞지 않고
 * 「직접 확인할 것」으로 따로 안내한다 — 서비스 기능 정의서 RISK-05.
 *
 * 상세 패널의 한 섹션이다 — 좌우 패딩과 섹션 사이 띠는 패널이 갖는다.
 */
export const PersonalConditions = memo(function PersonalConditions({ conditions }: PersonalConditionsProps) {
  return (
    <section aria-label="직접 확인할 조건">
      <h3 className={`${styles.title} type-heading-3`}>직접 확인할 조건</h3>

      <Alert variant="info" className={styles.notice}>
        아래 항목은 시스템이 판정하지 않습니다. 가입 전에 직접 확인하세요.
      </Alert>

      {conditions.length === 0 ? (
        <p className={`${styles.empty} type-body`}>없음</p>
      ) : (
        <ul className={`${styles.list} type-body`}>
          {conditions.map((condition) => (
            <li key={condition}>{personalConditionLabel(condition)}</li>
          ))}
        </ul>
      )}
    </section>
  );
});
