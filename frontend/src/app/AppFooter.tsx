import { Link } from 'react-router';
import styles from './AppFooter.module.css';

/** 사이트맵 링크. 내부 이동은 라우터 `Link`(`to`), 외부는 새 탭 `<a>`(`href`) — 둘 중 하나만 갖는다 */
type FooterLink = { label: string; to: string; href?: never } | { label: string; href: string; to?: never };

interface FooterColumn {
  title: string;
  links: FooterLink[];
}

/**
 * 구역 1 sitemap 의 3컬럼. 참고 사이트는 6컬럼이지만 우리 라우트가 7개라 빈 컬럼이 생긴다 (#112 계획).
 * 「내 정보」는 인증 필수 화면이라 비로그인에서 누르면 가드가 `/login` 으로 보낸다 — 숨기지 않는다.
 * 「데이터 출처」는 데이터 적재 방침 문서가 정한 출처다.
 */
const SITEMAP: FooterColumn[] = [
  {
    title: '매물',
    links: [{ label: '지도 탐색', to: '/' }],
  },
  {
    title: '내 정보',
    links: [
      { label: '관심 매물', to: '/me/wishlist' },
      { label: '알림', to: '/notifications' },
      { label: '알림 설정', to: '/me/notification-subscriptions' },
      { label: '계정 · 자격 정보', to: '/me/profile' },
    ],
  },
  {
    title: '데이터 출처',
    links: [
      { label: '국토교통부 실거래가 · 건축물대장', href: 'https://www.data.go.kr' },
      { label: '도로명주소', href: 'https://business.juso.go.kr' },
      { label: '한국은행 ECOS', href: 'https://ecos.bok.or.kr' },
      { label: '금융상품 통합비교공시', href: 'https://finlife.fss.or.kr' },
    ],
  },
];

/** 구역 3 — 문단 하나가 줄의 배열이다. 줄 피치 20 은 `type-body-sm`(13/1.55)의 행간이 만든다 */
const PROJECT_INFO: string[][] = [
  [
    '전월세 부동산 금융 플랫폼',
    '서울시 전월세 매물의 전세사기 위험도를 등기 · 건축물대장 · 시세 · 보증보험 기준으로 판정합니다.',
  ],
  [
    '등기 정보는 현재 시연용 모의 데이터입니다. 실제 계약 전 등기부등본 원본을 반드시 확인하세요.',
    '개인 학습 · 포트폴리오 목적의 비상업 프로젝트입니다.',
  ],
];

const REPOSITORY_URL = 'https://github.com/Kim-buyeon/Real-estate-rental-platform';

const DISCLAIMER = '위험도 판정과 대출 한도는 공개 데이터에 근거한 참고 정보이며 법적 효력이 없습니다.';

const COPYRIGHT = '© 2026 전월세 부동산 금융 플랫폼';

const scrollToTop = () => window.scrollTo({ top: 0, behavior: 'smooth' });

/**
 * 전 페이지 공통 푸터. 구역 순서 · 여백 리듬 · 구분선 위치의 정본은 `design/examples/home/layout.md` 이고
 * 색 · 컴포넌트는 디자인 토큰 정의서의 `{components.site-footer}` 다. 내용은 우리 것으로 바꿨다 (#112 계획).
 *
 * 지도 화면은 이 푸터를 렌더하지 않는다 — 판정은 라우트 표의 `handle` 을 읽는 `AppShell` 이 한다.
 */
export function AppFooter() {
  return (
    <footer className={styles.footer}>
      {/* 구역 1 — sitemap. 유일한 흰 면이다 */}
      <div className={styles.sitemap}>
        <nav className={`${styles.container} ${styles.sitemapColumns}`} aria-label="사이트맵">
          {SITEMAP.map((column) => (
            <section key={column.title}>
              <h2 className={`${styles.columnTitle} type-eyebrow`}>{column.title}</h2>
              <ul>
                {column.links.map((link) => (
                  <li key={link.label}>
                    {link.to !== undefined ? (
                      <Link to={link.to} className={`${styles.sitemapLink} type-link`}>
                        {link.label}
                      </Link>
                    ) : (
                      <a
                        href={link.href}
                        target="_blank"
                        rel="noreferrer"
                        className={`${styles.sitemapLink} type-link`}
                      >
                        {link.label}
                      </a>
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

          {/* 구역 3 · 4 — 프로젝트 정보 · 저장소 버튼 · 저작권. 셋 다 같은 24 리듬이다 */}
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
            <div className={styles.buttonRow}>
              <a
                href={REPOSITORY_URL}
                target="_blank"
                rel="noreferrer"
                className={`${styles.footerButton} type-button-sm`}
              >
                GitHub 저장소
              </a>
            </div>
            <p className="type-body-sm">{COPYRIGHT}</p>
          </div>
        </div>
      </div>
    </footer>
  );
}
