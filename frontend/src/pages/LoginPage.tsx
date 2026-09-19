import { useEffect } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { Alert, CardForm } from '../components/ui';
import { LoginForm } from '../features/user';
import { LOGIN_REDIRECT_PARAM } from '../lib/routes';
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
 *
 * 카드 규격 · 여백은 레이아웃 맵 design/examples/login/layout.md 를 따른다.
 */
export default function LoginPage() {
  const [searchParams] = useSearchParams();
  const { isAuthenticated } = useSession();
  const navigate = useNavigate();
  const redirectTo = safeRedirect(searchParams.get(LOGIN_REDIRECT_PARAM));
  const isSignedUp = searchParams.get('signedUp') === '1';

  useEffect(() => {
    if (isAuthenticated) void navigate(redirectTo, { replace: true });
  }, [isAuthenticated, navigate, redirectTo]);

  if (isAuthenticated) return null;

  return (
    <section className={styles.page}>
      <CardForm>
        <h1 className="type-heading-1">로그인</h1>
        <hr className={styles.divider} />
        {isSignedUp && (
          <Alert variant="info" className={styles.alert}>
            가입이 완료되었습니다. 로그인해 주세요.
          </Alert>
        )}
        <p className={`${styles.lead} type-body-strong`}>가입한 이메일과 비밀번호를 입력해 주세요.</p>
        <div className={styles.formSlot}>
          <LoginForm />
        </div>
        <p className={`${styles.links} type-link`}>
          계정이 없으신가요? <Link to="/signup">회원가입</Link>
        </p>
      </CardForm>
    </section>
  );
}
