import { useState, type ChangeEvent, type FormEvent } from 'react';
import type { PropertyFilter } from '../api/property';
import {
  Badge,
  CardCategory,
  CardContent,
  PromoPanel,
  PromoPanelTile,
  Tabs,
  useComingSoon,
  type TabItem,
} from '../components/ui';
import { contractTypeLabel, propertyTypeLabel } from '../domain/property';
import { riskGradeLabel } from '../domain/risk';
import { RecentProperties } from '../features/property';
import { RiskCriteriaCards, RiskGradeBadges } from '../features/risk';
import styles from './MainPage.module.css';

interface RecentChip {
  id: string;
  label: string;
  /** 목록 조회의 공통 필터(매물 API 명세 1.1) — contractType · riskGrade · propertyType 하나씩 */
  filter: PropertyFilter;
}

/**
 * 「최근 등록 매물」 칩 줄. 칩 하나 = 목록 조회 조건 하나다. 모듈 상수라 같은 칩은 늘 같은 필터 객체를 넘긴다.
 * 문구는 열거값의 매핑에서 읽는다 — 「전세」 · 「안전」을 여기 다시 적지 않는다.
 */
const RECENT_CHIPS: readonly RecentChip[] = [
  { id: 'all', label: '전체', filter: {} },
  { id: 'deposit-only', label: contractTypeLabel('DEPOSIT_ONLY'), filter: { contractType: 'DEPOSIT_ONLY' } },
  { id: 'monthly-rent', label: contractTypeLabel('MONTHLY_RENT'), filter: { contractType: 'MONTHLY_RENT' } },
  { id: 'safe', label: riskGradeLabel('SAFE'), filter: { riskGrade: ['SAFE'] } },
  { id: 'apartment', label: propertyTypeLabel('APARTMENT'), filter: { propertyType: 'APARTMENT' } },
  { id: 'officetel', label: propertyTypeLabel('OFFICETEL'), filter: { propertyType: 'OFFICETEL' } },
];

const RECENT_CHIP_ITEMS: readonly TabItem[] = RECENT_CHIPS.map(({ id, label }) => ({ id, label }));

// RECENT_CHIPS 의 첫 칩(전체). 배열 인덱싱은 noUncheckedIndexedAccess 로 undefined 가 섞이므로 값으로 적는다
const DEFAULT_CHIP_ID = 'all';

/** 「준비 중」 카테고리 카드 셋 — 차기 범위 기능의 입구다(기능 정의 개요 3장). 누르면 토스트만 뜬다 */
const COMING_SOON_CATEGORIES: readonly { title: string; description: string }[] = [
  { title: '전월세 전환율 계산', description: '전세 보증금과 월세를 서로 바꿔 계산합니다.' },
  { title: '보증 신청기한 계산', description: '계약 기간으로 보증보험 신청 기한을 확인합니다.' },
  { title: '대출 상품 추천', description: '내 조건에 맞는 전세자금대출 상품을 골라 드립니다.' },
];

/** 「전세 계약 가이드」 콘텐츠 카드 다섯 — 글은 아직 없다. 누르면 토스트만 뜬다 */
const GUIDES: readonly { label: string; heading: string }[] = [
  { label: '용어', heading: '깡통전세란 무엇인가요' },
  { label: '보증보험', heading: '보증기관 3곳은 무엇이 다른가요' },
  { label: '등기', heading: '등기부등본 보는 법' },
  { label: '시세', heading: '전세가율은 어떻게 계산하나요' },
  { label: '대출', heading: '전세자금대출 한도는 어떻게 정해지나요' },
];

/*
 * 아이콘 모양은 정의서가 다루지 않는다(6절 Icon — 브랜드 요소). 획 약 1.5 의 선 아이콘이다.
 * 컴포넌트가 아니라 모듈 상수 엘리먼트다 — 파일 하나에 컴포넌트 하나(MainPage)를 지킨다
 */

const SEARCH_ICON = (
  <svg viewBox="0 0 20 20" aria-hidden="true">
    <circle cx="9" cy="9" r="6" fill="none" stroke="currentColor" strokeWidth="1.5" />
    <line x1="13.5" y1="13.5" x2="18" y2="18" stroke="currentColor" strokeWidth="1.5" />
  </svg>
);

const CHAT_ICON = (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M4 5h16v11H9l-5 4z" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
  </svg>
);

const HISTORY_ICON = (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" strokeWidth="1.5" />
    <polyline points="12,7 12,12 15,14" fill="none" stroke="currentColor" strokeWidth="1.5" />
  </svg>
);

/**
 * `/` 메인 화면. 조합만 한다 — 매물 조회는 RecentProperties 가, 등급 · 판정 기준 설명은 features/risk 가,
 * 준비 중 알림은 공용 useComingSoon 이 갖는다. 이 화면이 소유하는 상태는 검색 입력과 고른 칩 둘이다.
 *
 * 구역 순서 · 크기의 정본은 레이아웃 맵 home 「페이지 섹션 순서」(1007 계측)이고 각 주석에 구역 번호를 적었다.
 * 이슈 487 계획 —
 * - 지도로 가는 길은 상단 내비게이션 하나다. 본문에 지도 링크를 두지 않는다(매물 카드의 상세 딥링크는 예외 — 사용자 결정 (a))
 * - 검색 바는 모양만 있다 — 키워드 검색 엔드포인트가 없어 입력 · 제출하면 준비 중 토스트가 뜬다
 * - 사진 · 일러스트를 쓰지 않는다
 */
export default function MainPage() {
  const comingSoon = useComingSoon();
  const [keyword, setKeyword] = useState('');
  const [chipId, setChipId] = useState(DEFAULT_CHIP_ID);

  const selectedChip = RECENT_CHIPS.find((chip) => chip.id === chipId);

  // 입력을 시작한 순간 한 번 알린다 — 글자마다 토스트를 갈아 끼우면 화면 낭독기가 매번 다시 읽는다
  const handleKeywordChange = (event: ChangeEvent<HTMLInputElement>) => {
    const next = event.target.value;
    if (keyword === '' && next !== '') comingSoon();
    setKeyword(next);
  };

  const handleSearchSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    comingSoon();
  };

  return (
    <div className={styles.page}>
      {/* 구역 2 · 3 — hero 검색 바 + 카테고리 카드 · 우 레일. 한 회색 면이 화면 전폭으로 깔린다 */}
      <div className={styles.top}>
        <div className={styles.container}>
          <form role="search" className={styles.search} onSubmit={handleSearchSubmit}>
            <button type="submit" className={styles.searchButton} aria-label="검색">
              {SEARCH_ICON}
            </button>
            <input
              type="search"
              className={`${styles.searchInput} type-body`}
              placeholder="지역 · 단지명으로 검색"
              aria-label="지역 · 단지명으로 검색"
              value={keyword}
              onChange={handleKeywordChange}
            />
          </form>

          {/*
            좌 카드 · 우 레일 2단. 큰 배너가 h1 이라 DOM 에서 맨 앞에 두고 그리드가 우 레일 자리로 옮긴다 —
            배너에는 누를 것이 없어 키보드 순서(카드 → 상담 타일)는 화면 순서와 같다
          */}
          <div className={styles.categoryArea}>
            <section className={styles.banner} aria-labelledby="main-title">
              <p className={`${styles.bannerEyebrow} type-eyebrow`}>서울시 전월세 매물 위험 등급</p>
              <h1 id="main-title" className={`${styles.bannerTitle} type-heading-1`}>
                전세사기 위험 등급,
                <br />
                계약 전에 확인하세요
              </h1>
              <p className={`${styles.bannerLead} type-body`}>
                등기 · 건축물대장 · 시세 · 보증보험 기준을 대조해 매물의 위험 등급을 3단계로 보여 줍니다.
              </p>
            </section>

            <ul className={styles.categories} aria-label="서비스 안내">
              <li className={styles.categoryWide}>
                <CardCategory
                  title="전세사기 위험 등급"
                  description="매물마다 네 가지 자료를 대조해 3단계로 판정합니다."
                  footer={<RiskGradeBadges />}
                />
              </li>
              <li className={styles.categoryWide}>
                <CardCategory
                  title="전세자금대출 한도"
                  description="매물 상세에서 내 자격 정보로 대출 한도를 계산합니다."
                />
              </li>
              {COMING_SOON_CATEGORIES.map((category) => (
                <li key={category.title} className={styles.categoryNarrow}>
                  <CardCategory
                    title={category.title}
                    description={category.description}
                    badge={<Badge variant="neutral">준비 중</Badge>}
                    onClick={comingSoon}
                  />
                </li>
              ))}
            </ul>

            <div className={styles.promo}>
              <PromoPanel title="전세 계약 상담" description="계약 전 궁금한 점을 상담으로 풀어 드립니다.">
                <PromoPanelTile icon={CHAT_ICON} label="위험 등급 해설 상담" onClick={comingSoon} />
                <PromoPanelTile icon={HISTORY_ICON} label="상담 이력" onClick={comingSoon} />
              </PromoPanel>
            </div>
          </div>
        </div>
      </div>

      {/* 구역 4 — recommend. 제목 · 칩 줄 · 추천 래퍼(4열) */}
      <section className={`${styles.container} ${styles.recommend}`} aria-labelledby="recent-title">
        <h2 id="recent-title" className={`${styles.sectionTitle} type-section-title`}>
          최근 등록 매물
        </h2>
        <Tabs
          variant="pill"
          label="최근 등록 매물 조건"
          items={RECENT_CHIP_ITEMS}
          selectedId={chipId}
          onSelect={setChipId}
        >
          <div className={styles.recommendWrapper}>
            <RecentProperties filter={selectedChip?.filter} />
          </div>
        </Tabs>
      </section>

      {/* 구역 5 — feature-band. 어두운 띠가 화면 전폭으로 깔린다. 슬라이드 없이 카드 넷을 정적으로 둔다 */}
      <section className={styles.band} aria-labelledby="criteria-title">
        <div className={styles.container}>
          <h2 id="criteria-title" className={`${styles.bandTitle} type-section-title`}>
            위험 등급 판정 기준
          </h2>
          <RiskCriteriaCards />
        </div>
      </section>

      {/* 구역 6 — content-cards 5열. 글은 아직 없다 */}
      <section className={`${styles.container} ${styles.guides}`} aria-labelledby="guide-title">
        <h2 id="guide-title" className={`${styles.sectionTitle} type-section-title`}>
          전세 계약 가이드
        </h2>
        <ul className={styles.guideGrid}>
          {GUIDES.map((guide) => (
            <li key={guide.heading}>
              <CardContent label={guide.label} heading={guide.heading} onClick={comingSoon} />
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
