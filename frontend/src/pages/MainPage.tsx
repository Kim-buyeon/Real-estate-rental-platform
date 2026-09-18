import { Link } from 'react-router';
import { buttonClassName } from '../components/ui';
import { RecentProperties } from '../features/property';
import { RiskGradeGuide } from '../features/risk';
import styles from './MainPage.module.css';

/** 지도 탐색 경로. 히어로 버튼 · 「전체 보기」가 같은 곳으로 간다 */
const MAP_PATH = '/map';

/**
 * `/` 메인 화면. 조합만 한다 — 쿼리는 RecentProperties 가, 등급 설명은 RiskGradeGuide 가 갖는다.
 *
 * 섹션 셋이다. 배치의 정본은 레이아웃 맵 home 이고 구역 번호를 각 주석에 적었다.
 * 검색 입력은 만들지 않는다 — 키워드 검색 엔드포인트가 없다 (이슈 117 계획).
 * 데이터 출처는 푸터가 갖는다. 한 화면에 두 번 적지 않는다.
 */
export default function MainPage() {
  return (
    <div className={styles.page}>
      {/* 구역 2 — hero. 참고 사이트가 검색 바를 두던 자리의 크기와 리듬을 쓴다 */}
      <section className={styles.hero} aria-labelledby="hero-title">
        <div className={`${styles.container} ${styles.heroInner}`}>
          <h1 id="hero-title" className={`${styles.heroTitle} type-display`}>
            전세사기 위험도를 지도에서 확인합니다
          </h1>
          <p className={`${styles.heroLead} type-body-lg`}>
            서울시 전월세 매물을 등기 · 건축물대장 · 시세 · 보증보험 기준으로 대조해 위험도를 3단계로 보여 주는
            참고 서비스입니다.
          </p>
          <Link to={MAP_PATH} className={buttonClassName('primary', 'md')}>
            지도에서 매물 찾기
          </Link>
        </div>
      </section>

      <div className={`${styles.container} ${styles.sections}`}>
        {/* 구역 3 — 카테고리 카드 자리. 참고 사이트의 매물 유형 대신 등급 설명을 둔다 */}
        <section className={styles.section} aria-labelledby="risk-guide-title">
          <div className={styles.sectionHead}>
            <h2 id="risk-guide-title" className={`${styles.sectionTitle} type-heading-2`}>
              위험도 3단계
            </h2>
          </div>
          <RiskGradeGuide />
        </section>

        {/* 구역 4 — 추천 매물 그리드 자리. 우리는 목록 조회의 기본 정렬(등록일 최신순)을 그대로 쓴다 */}
        <section className={styles.section} aria-labelledby="recent-title">
          <div className={styles.sectionHead}>
            <h2 id="recent-title" className={`${styles.sectionTitle} type-heading-2`}>
              최근 등록 매물
            </h2>
            {/* 푸터 사이트맵 링크와 같은 어휘다 — 이동은 라우터 Link, 글자는 type-link */}
            <Link to={MAP_PATH} className={`${styles.moreLink} type-link`}>
              전체 보기
            </Link>
          </div>
          <div className={styles.gridWrapper}>
            <RecentProperties />
          </div>
        </section>
      </div>
    </div>
  );
}
