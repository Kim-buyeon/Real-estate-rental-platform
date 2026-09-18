import { Badge, Card } from '../../../components/ui';
import { RISK_GRADES, riskGradeLabel, riskGradeToken, type RiskGrade } from '../../../domain/risk';
import styles from './RiskGradeGuide.module.css';

interface GradeGuide {
  /** 등급이 갈리는 기준 한 줄 */
  criteria: string;
  /** 그 기준이 무엇을 뜻하는지 한 줄 */
  description: string;
}

/**
 * 메인 화면의 등급 설명 문구. 값은 판정 기준 문서 3장 「등급 기준」 표 그대로다.
 *
 * **판정에 쓰지 않는다 — 여기 적힌 70 · 80은 설명용 사본이고, 정본은 서버 기준값(RISK_CRITERIA)이다.**
 * 그래서 domain/risk.ts에 두지 않았다: 그 파일은 등급 코드의 이름만 갖고 임계 수치를 문구에 넣지
 * 않기로 정해져 있다. 기준값이 바뀌면 이 표를 판정 기준 문서와 함께 고친다.
 */
const GRADE_GUIDE: Record<RiskGrade, GradeGuide> = {
  SAFE: {
    criteria: '보증보험 가입 가능 · 전세가율 70% 이하',
    description: '보증보험 3사 중 한 곳 이상이 가입 가능으로 본 집입니다.',
  },
  CAUTION: {
    criteria: '보증보험 가입 가능 · 전세가율 70% 초과 80% 이하',
    description: '가입은 되지만 전세가율이 깡통전세 선에 가깝습니다.',
  },
  DANGER: {
    criteria: '전세가율 80% 초과 또는 보증보험 3사 모두 가입 불가',
    description: '보증금을 돌려받지 못할 위험이 큰 구간입니다.',
  },
};

/** 판정에 쓰는 자료 넷. 무엇을 보는지만 적는다 — 산식과 임계값은 서버가 갖는다 */
const EVIDENCE: { title: string; description: string }[] = [
  { title: '등기', description: '선순위채권과 권리관계를 확인합니다' },
  { title: '건축물대장', description: '주거용 표기와 위반건축물 여부를 봅니다' },
  { title: '시세', description: '실거래가로 전세가율을 계산합니다' },
  { title: '보증보험', description: '3사의 가입 기준에 맞는지 대조합니다' },
];

/**
 * 위험 등급 3단계 설명 (RISK-01). 메인 화면의 카드 그리드 자리다 —
 * 레이아웃 맵 home 「구역 3 — 카테고리 카드 그리드」의 치수를 쓴다.
 *
 * 데이터를 부르지 않는 표현 컴포넌트다. 등급의 순서 · 문구 · 배지 색은 domain/risk.ts가 갖고
 * 여기서는 읽기만 한다 — 조건 분기로 다시 만들지 않는다 (frontend/CLAUDE.md 타입·열거값).
 */
export function RiskGradeGuide() {
  return (
    <div className={styles.guide}>
      <ul className={styles.grades}>
        {RISK_GRADES.map((grade) => (
          <li key={grade}>
            <Card className={styles.card}>
              <Badge variant={riskGradeToken(grade)}>{riskGradeLabel(grade)}</Badge>
              <p className={`${styles.criteria} type-body-strong`}>{GRADE_GUIDE[grade].criteria}</p>
              <p className={`${styles.description} type-body`}>{GRADE_GUIDE[grade].description}</p>
            </Card>
          </li>
        ))}
      </ul>

      <div className={styles.evidence}>
        <p className={`${styles.evidenceTitle} type-body-strong`}>판정 근거</p>
        <ul className={styles.evidenceItems}>
          {EVIDENCE.map((item) => (
            <li key={item.title} className={styles.evidenceItem}>
              <span className="type-body-sm">{item.title}</span>
              <span className={`${styles.evidenceDescription} type-body-sm`}>{item.description}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
