import { CardFeature, type CardFeatureItem } from '../../../components/ui';
import { riskGradeLabel } from '../../../domain/risk';
import styles from './RiskCriteriaCards.module.css';

interface Criterion {
  /** 판정에 쓰는 자료 이름 — 카드 좌상단 배지 */
  badge: string;
  title: string;
  description: string;
  items: readonly CardFeatureItem[];
}

/**
 * 판정에 쓰는 자료 넷과 무엇을 보는지. 산식과 임계값의 정본은 서버(판정 기준 문서 2 ~ 4장 · RISK_CRITERIA)다.
 *
 * **판정에 쓰지 않는다 — 여기 적힌 70 · 80은 설명용 사본이다.** domain/risk.ts 는 등급 코드의 이름만 갖고 임계 수치를
 * 문구에 넣지 않기로 정해져 있어 여기 둔다. 기준값이 바뀌면 이 표를 판정 기준 문서와 함께 고친다.
 * 등급 이름은 domain/risk.ts 의 매핑에서 읽는다. 등기는 개방 API 가 없어 예시 데이터다(데이터 적재 문서 1.3) — 푸터 안내와 같다.
 */
const CRITERIA: readonly Criterion[] = [
  {
    badge: '등기',
    title: '선순위채권 · 권리관계',
    description: '근저당 채권최고액과 압류 · 가압류 · 경매 · 신탁 등기가 있는지 봅니다.',
    items: [
      { label: '보는 것', value: '선순위채권 · 권리 침해' },
      { label: '자료', value: '예시 데이터' },
    ],
  },
  {
    badge: '건축물대장',
    title: '주택 상태',
    description: '위반건축물인지, 대장 주소가 등기 주소와 맞는지 봅니다.',
    items: [
      { label: '보는 것', value: '위반건축물 · 주소 일치' },
      { label: '자료', value: '건축HUB 건축물대장' },
    ],
  },
  {
    badge: '시세',
    title: '전세가율',
    description: '같은 동 · 유형 · 면적대의 매매 실거래가 중앙값을 시세로 보고, 선순위채권과 보증금의 합을 시세로 나눕니다.',
    items: [
      { label: riskGradeLabel('CAUTION'), value: '70% 초과' },
      { label: riskGradeLabel('DANGER'), value: '80% 초과' },
    ],
  },
  {
    badge: '보증보험',
    title: '보증기관 3곳 가입 가능 여부',
    description: 'HUG · HF · SGI 의 집 단위 가입 조건에 맞는지 기관마다 대조합니다.',
    items: [
      { label: `${riskGradeLabel('SAFE')} · ${riskGradeLabel('CAUTION')}`, value: '한 곳 이상 가입 가능' },
      { label: riskGradeLabel('DANGER'), value: '보증기관 3곳 모두 가입 불가' },
    ],
  },
];

/**
 * 위험도 판정 기준 카드 넷 (RISK-01 설명). 메인의 {colors.block-feature} 띠 안에 놓인다 — 띠와 제목은 쓰는 화면이 갖는다.
 * 데이터를 부르지 않는 표현 컴포넌트다.
 */
export function RiskCriteriaCards() {
  return (
    <ul className={styles.grid}>
      {CRITERIA.map((criterion) => (
        <li key={criterion.badge}>
          <CardFeature
            badge={criterion.badge}
            title={criterion.title}
            description={criterion.description}
            items={criterion.items}
          />
        </li>
      ))}
    </ul>
  );
}
