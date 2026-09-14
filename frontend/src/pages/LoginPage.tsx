import { useEffect } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { Alert, Card } from '../components/ui';
import { LoginForm } from '../features/user';
import { useSession } from '../session/useSession';
import styles from './LoginPage.module.css';

const HOME_PATH = '/';

/** 같은 오리진 경로만 — `/`로 시작하고 `//`로 시작하지 않을 때. 그 밖은 오픈 리다이렉트라 홈으로 */
function safeRedirect(value: string | null): string {
  if (value && value.startsWith('/') && !value.startsWith('//')) return value;
  return HOME_PATH;
}

/**
 * `/login` — USER-02. 로그인 상태가 되면(로그인 성공 · 이미 로그인한 채 진입) ?redirect= 경로, 없으면 `/`로 replace 이동.
 * 이동은 로그인 상태 한 곳을 보고 한다 — 폼 성공 콜백과 둘로 두면 두 번 이동한다.
 */
export default function LoginPage() {
  const [searchParams] = useSearchParams();
  const { isAuthenticated } = useSession();
  const navigate = useNavigate();
  const redirectTo = safeRedirect(searchParams.get('redirect'));
  const isSignedUp = searchParams.get('signedUp') === '1';

  useEffect(() => {
    if (isAuthenticated) void navigate(redirectTo, { replace: true });
  }, [isAuthenticated, navigate, redirectTo]);

  if (isAuthenticated) return null;

  return (
    <section className={styles.page}>
      <Card className={styles.card}>
        <h1 className="type-heading-2">로그인</h1>
        {isSignedUp && <Alert variant="info">가입이 완료되었습니다. 로그인해 주세요.</Alert>}
        <LoginForm />
        <p className={`${styles.footer} type-body`}>
          계정이 없으신가요? <Link to="/signup">회원가입</Link>
        </p>
      </Card>
    </section>
  );
}
