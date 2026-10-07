import { CardForm } from '../components/ui';
import { ProfileForm } from '../features/user';
import styles from './ProfilePage.module.css';

/**
 * `/me/profile` — USER-03 계정 · 자격 정보. 인증 필수이며 가드는 라우터의 RequireAuth다.
 * 조합만 한다 — 조회 · 수정은 ProfileForm이 쿼리 정의를 거쳐 한다.
 *
 * 메뉴 묶음 「내 정보」의 첫 탭(「계정」)이다 — 페이지 제목과 탭은 묶음 머리(app/SectionLayout)가 그린다 (이슈 489).
 * 카드 규격 · 여백은 레이아웃 맵 design/examples/my-info/layout.md 를 따른다.
 */
export default function ProfilePage() {
  return (
    <section className={styles.page} aria-label="계정">
      <CardForm className={styles.card}>
        <ProfileForm />
      </CardForm>
    </section>
  );
}
