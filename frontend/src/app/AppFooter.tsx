import { Link } from 'react-router';
import { useComingSoon } from '../components/ui';
import styles from './AppFooter.module.css';

/**
 * 사이트맵 항목 셋 —
 *   link  내부 화면으로 가는 라우터 Link
 *   soon  아직 만들지 않은 기능의 입구. 링크 모양의 버튼이고 누르면 「준비 중」 토스트만 띄운다(이동 없음)
 *   text  누를 수 없는 이름(데이터 출처)
 * 외부 링크는 두지 않는다 — 데이터 출처도 이름만 적는다(이슈 487).
 */
type FooterItem =
  | { kind: 'link'; label: string; to: string }
  | { kind: 'soon'; label: string }
  | { kind: 'text'; label: string };

interface FooterColumn {
  title: string;
  items: FooterItem[];
}

/**
 * 구역 1 sitemap 의 6컬럼(레이아웃 맵 home 구역 7 — 컬럼 6). 이슈 487 계획 —
 * - 지도로 가는 길은 상단 내비게이션 하나다. 「매물」 컬럼에도 지도 링크를 두지 않는다
 * - 「내 정보」는 인증 필수 화면이라 비로그인에서 누르면 가드가 `/login` 으로 보낸다 — 숨기지 않는다
 * - 「매물」 · 「대출」 · 「상담」 · 「고객지원」은 차기 범위이거나 화면이 없는 기능의 입구다 — 준비 중 토스트
 * - 「데이터 출처」는 데이터 적재 방침 문서가 정한 출처의 이름이다
 */
const SITEMAP: FooterColumn[] = [
  {
    title: '매물',
    items: [
      { kind: 'soon', label: '매물 검색' },
      { kind: 'soon', label: '시세 추이' },
      { kind: 'soon', label: '전월세 전환율 계산' },
      { kind: 'soon', label: '보증 신청기한 계산' },
    ],
  },
  {
    title: '내 정보',
    items: [
      { kind: 'link', label: '관심 매물', to: '/me/wishlist' },
      { kind: 'link', label: '알림', to: '/notifications' },
      { kind: 'link', label: '알림 설정', to: '/me/notification-subscriptions' },
      { kind: 'link', label: '계정 · 자격 정보', to: '/me/profile' },
    ],
  },
  {
    title: '대출',
    items: [
      { kind: 'soon', label: '대출 상품 추천' },
      { kind: 'soon', label: '상환 시뮬레이션' },
    ],
  },
  {
    title: '상담',
    items: [
      { kind: 'soon', label: '위험도 해설 상담' },
      { kind: 'soon', label: '상담 이력' },
    ],
  },
  {
    title: '고객지원',
    items: [
      { kind: 'soon', label: '공지사항' },
      { kind: 'soon', label: '자주 묻는 질문' },
      { kind: 'soon', label: '문의하기' },
    ],
  },
  {
    title: '데이터 출처',
    items: [
      { kind: 'text', label: '국토교통부 실거래가 · 건축물대장' },
      { kind: 'text', label: '도로명주소' },
      { kind: 'text', label: '카카오 로컬' },
      { kind: 'text', label: '한국은행 ECOS' },
      { kind: 'text', label: '금융상품 통합비교공시' },
    ],
  },
];

/** 구역 3 — 문단 하나가 줄의 배열이다. 줄 피치 20 은 `type-body-sm`(13/1.55)의 행간이 만든다 */
const PROJECT_INFO: string[][] = [
  [
    '전월세 부동산 금융 플랫폼',
    '서울시 전월세 매물의 전세사기 위험도를 등기 · 건축물대장 · 시세 · 보증보험 기준으로 판정합니다.',
  ],
  ['등기 정보는 예시이며, 계약 전 등기부등본 원본을 반드시 확인하세요.'],
];

const DISCLAIMER = '위험도 판정과 대출 한도는 공개 데이터에 근거한 참고 정보이며 법적 효력이 없습니다.';

const COPYRIGHT = '© 2026 전월세 부동산 금융 플랫폼';

const scrollToTop = () => window.scrollTo({ top: 0, behavior: 'smooth' });

/**
 * 전 페이지 공통 푸터. 구역 순서 · 여백 리듬 · 구분선 위치의 정본은 `design/examples/home/layout.md` 이고
 * 색 · 컴포넌트는 디자인 토큰 정의서의 `{components.site-footer}` 다. 내용은 우리 것으로 바꿨다 (이슈 112 · 487 계획).
 *
 * 지도 화면은 이 푸터를 렌더하지 않는다 — 판정은 라우트 표의 `handle` 을 읽는 `AppShell` 이 한다.
 */
export function AppFooter() {
  const comingSoon = useComingSoon();

  return (
    <footer className={styles.footer}>
      {/* 구역 1 — sitemap. 유일한 흰 면이다 */}
      <div className={styles.sitemap}>
        <nav className={`${styles.container} ${styles.sitemapColumns}`} aria-label="사이트맵">
          {SITEMAP.map((column) => (
            <section key={column.title}>
              <h2 className={`${styles.columnTitle} type-eyebrow`}>{column.title}</h2>
              <ul>
                {column.items.map((item) => (
                  <li key={item.label}>
                    {item.kind === 'link' && (
                      <Link to={item.to} className={`${styles.sitemapLink} type-body-sm`}>
                        {item.label}
                      </Link>
                    )}
                    {item.kind === 'soon' && (
                      <button
                        type="button"
                        className={`${styles.sitemapLink} ${styles.sitemapButton} type-body-sm`}
                        onClick={comingSoon}
                      >
                        {item.label}
                      </button>
                    )}
                    {item.kind === 'text' && (
                      <span className={`${styles.sitemapText} type-body-sm`}>{item.label}</span>
                    )}
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </nav>
      </div>

      {/* 구역 2~4 — 어두운 면 하나. 배경은 화면 전폭, 내용과 구분선은 컨테이너 폭이다 */}
      <div className={styles.dark}>
        <div className={styles.container}>
          {/* 구역 2 — link-bar. 좌측 면책 · 우측 끝 TOP 셀. 하단 구분선은 푸터의 유일한 가로선이다 */}
          <div className={styles.linkBar}>
            <p className={`${styles.disclaimer} type-link`}>{DISCLAIMER}</p>
            <button type="button" className={styles.topCell} onClick={scrollToTop}>
              <svg className={styles.topArrow} viewBox="0 0 8 8" aria-hidden="true">
                <polygon points="4,0 8,8 0,8" fill="currentColor" />
              </svg>
              <span className="type-caption">TOP</span>
            </button>
          </div>

          {/* 구역 3 · 4 — 프로젝트 정보 · 저작권. 문단 사이와 저작권 위가 같은 24 리듬이다 */}
          <div className={styles.projectInfo}>
            {PROJECT_INFO.map((paragraph) => (
              <div key={paragraph.join('')}>
                {paragraph.map((line) => (
                  <p key={line} className="type-body-sm">
                    {line}
                  </p>
                ))}
              </div>
            ))}
            <p className="type-body-sm">{COPYRIGHT}</p>
          </div>
        </div>
      </div>
    </footer>
  );
}
