import { useCallback } from 'react';
import { Link, useNavigate } from 'react-router';
import { Card } from '../components/ui';
import { SignupForm } from '../features/user';
import styles from './SignupPage.module.css';

/** 가입 응답에 토큰이 없다(명세 1.2) — 로그인 화면으로 보내 안내한다 (이슈 #83 계획) */
const LOGIN_AFTER_SIGNUP_PATH = '/login?signedUp=1';

/** `/signup` — USER-01 이메일 가입 */
export default function SignupPage() {
  const navigate = useNavigate();
  const handleSignedUp = useCallback(() => {
    void navigate(LOGIN_AFTER_SIGNUP_PATH, { replace: true });
  }, [navigate]);

  return (
    <section className={styles.page}>
      <Card className={styles.card}>
        <h1 className="type-heading-2">회원가입</h1>
        <SignupForm onSignedUp={handleSignedUp} />
        <p className={`${styles.footer} type-body`}>
          이미 계정이 있으신가요? <Link to="/login">로그인</Link>
        </p>
      </Card>
    </section>
  );
}
