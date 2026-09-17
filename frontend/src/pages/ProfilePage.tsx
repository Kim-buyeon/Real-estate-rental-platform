import { Card } from '../components/ui';
import { ProfileForm } from '../features/user';
import styles from './ProfilePage.module.css';

/**
 * `/me/profile` — USER-03 계정 · 자격 정보. 인증 필수이며 가드는 라우터의 RequireAuth다.
 * 조합만 한다 — 조회 · 수정은 ProfileForm이 쿼리 정의를 거쳐 한다.
 */
export default function ProfilePage() {
  return (
    <section className={styles.page}>
      <Card className={styles.card}>
        <h1 className="type-heading-2">계정 · 자격 정보</h1>
        <ProfileForm />
      </Card>
    </section>
  );
}
