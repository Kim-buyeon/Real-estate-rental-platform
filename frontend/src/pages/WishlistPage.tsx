import { WishlistList } from '../features/property';
import styles from './WishlistPage.module.css';

/**
 * `/me/wishlist` — PROP-05 관심 매물. 인증 필수이며 가드는 라우터의 RequireAuth다.
 * 조합만 한다 — 조회 · 더 보기는 WishlistList가 쿼리 정의를 거쳐 한다.
 *
 * 메뉴 묶음 「관심 목록」의 첫 탭이다 — 페이지 제목과 탭은 묶음 머리(app/SectionLayout)가 그린다 (이슈 489).
 */
export default function WishlistPage() {
  return (
    <section className={styles.page} aria-label="관심 매물">
      <WishlistList />
    </section>
  );
}
