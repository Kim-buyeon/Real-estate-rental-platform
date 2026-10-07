import { Badge } from '../../../components/ui';
import { RISK_GRADES, riskGradeLabel, riskGradeToken } from '../../../domain/risk';
import styles from './RiskGradeBadges.module.css';

/**
 * 위험 등급 3단계 배지 줄 (RISK-01 설명). 메인 카테고리 카드 「전세사기 위험도」의 우하단 자리에 들어간다.
 *
 * 데이터를 부르지 않는 표현 컴포넌트다. 등급의 순서 · 문구 · 배지 색은 domain/risk.ts 가 갖고 여기서는 읽기만 한다 —
 * 조건 분기로 다시 만들지 않는다 (frontend/CLAUDE.md 타입·열거값). 등급 색은 배지로만 나온다(정의서 2절).
 */
export function RiskGradeBadges() {
  return (
    <ul className={styles.badges} aria-label="위험 등급 3단계">
      {RISK_GRADES.map((grade) => (
        <li key={grade}>
          <Badge variant={riskGradeToken(grade)}>{riskGradeLabel(grade)}</Badge>
        </li>
      ))}
    </ul>
  );
}
