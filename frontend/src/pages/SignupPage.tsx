import { useCallback } from 'react';
import { Link, useNavigate } from 'react-router';
import { CardForm } from '../components/ui';
import { SignupForm } from '../features/user';
import { loginAfterSignupPath } from '../lib/routes';
import styles from './SignupPage.module.css';

/**
 * `/signup` — USER-01 이메일 가입.
 * 카드 규격 · 여백은 레이아웃 맵 design/examples/signup/layout.md 를 따른다 — login 과 같은 골격이다.
 */
export default function SignupPage() {
  const navigate = useNavigate();
  const handleSignedUp = useCallback(() => {
    // 가입 응답에 토큰이 없다(명세 1.2) — 로그인 화면으로 보내 안내한다 (이슈 #83 계획)
    void navigate(loginAfterSignupPath(), { replace: true });
  }, [navigate]);

  return (
    <section className={styles.page}>
      <CardForm>
        <h1 className="type-heading-1">회원가입</h1>
        <hr className={styles.divider} />
        <p className={`${styles.lead} type-body-strong`}>가입에 필요한 정보를 입력해 주세요.</p>
        <div className={styles.formSlot}>
          <SignupForm onSignedUp={handleSignedUp} />
        </div>
        <p className={`${styles.links} type-link`}>
          이미 계정이 있으신가요? <Link to="/login">로그인</Link>
        </p>
      </CardForm>
    </section>
  );
}
