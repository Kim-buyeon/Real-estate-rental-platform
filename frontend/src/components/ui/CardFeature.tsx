import { Badge } from './Badge';
import styles from './CardFeature.module.css';

export interface CardFeatureItem {
  label: string;
  value: string;
}

interface CardFeatureProps {
  /** 좌상단 윗변에 붙는 배지 문구 — 정의서가 {components.badge-inverse} 하나만 쓰기로 정했다 */
  badge: string;
  title: string;
  description: string;
  /** 정보부의 라벨 · 값 열. 열 수는 항목 수와 같다 */
  items: readonly CardFeatureItem[];
}

/**
 * 디자인 토큰 정의서 7절 「델타 1007 — 카드 변형 셋」의 {components.card-feature} — {colors.block-feature} 띠 안의 카드.
 *
 * 관측의 이미지부는 사진 자리인데 우리 화면은 사진을 쓰지 않는다(K-35) — 그 자리에 제목과 설명을 둔다.
 * 정보부는 라벨 · 값 열이고 열 사이에 세로선이 있다. 데이터를 부르지 않는 표현 컴포넌트이고 도메인을 모른다.
 */
export function CardFeature({ badge, title, description, items }: CardFeatureProps) {
  return (
    <article className={styles.card}>
      <div className={styles.main}>
        <Badge variant="inverse" className={styles.badge}>
          {badge}
        </Badge>
        <h3 className={`${styles.title} type-heading-3`}>{title}</h3>
        <p className={`${styles.description} type-body-sm`}>{description}</p>
      </div>
      <dl className={styles.info}>
        {items.map((item) => (
          <div key={item.label} className={styles.infoItem}>
            <dt className={`${styles.infoLabel} type-caption`}>{item.label}</dt>
            <dd className={`${styles.infoValue} type-body-sm`}>{item.value}</dd>
          </div>
        ))}
      </dl>
    </article>
  );
}
