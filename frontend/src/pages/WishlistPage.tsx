import { WishlistList } from '../features/property';
import styles from './WishlistPage.module.css';

/**
 * `/me/wishlist` — PROP-05 관심 매물. 인증 필수이며 가드는 라우터의 RequireAuth다.
 * 조합만 한다 — 조회 · 더 보기는 WishlistList가 쿼리 정의를 거쳐 한다.
 */
export default function WishlistPage() {
  return (
    <section className={styles.page}>
      <h1 className={`${styles.title} type-display`}>관심 매물</h1>
      <p className={`${styles.note} type-body`}>등록 순으로 최근에 담은 매물이 먼저 보입니다.</p>
      <WishlistList />
    </section>
  );
}
