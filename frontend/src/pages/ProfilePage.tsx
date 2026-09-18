import { CardForm } from '../components/ui';
import { ProfileForm } from '../features/user';
import styles from './ProfilePage.module.css';

/**
 * `/me/profile` — USER-03 계정 · 자격 정보. 인증 필수이며 가드는 라우터의 RequireAuth다.
 * 조합만 한다 — 조회 · 수정은 ProfileForm이 쿼리 정의를 거쳐 한다.
 *
 * 카드 규격 · 여백은 레이아웃 맵 design/examples/my-info/layout.md 를 따른다. 참고 사이트와 달리
 * 우리는 탭이 아니라 라우트 둘이라 탭 줄이 없다.
 */
export default function ProfilePage() {
  return (
    <section className={styles.page}>
      <h1 className={`${styles.title} type-display`}>계정 · 자격 정보</h1>
      <CardForm className={styles.card}>
        <ProfileForm />
      </CardForm>
    </section>
  );
}
