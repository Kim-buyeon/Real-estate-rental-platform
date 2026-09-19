import { useEffect } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { Alert, CardForm } from '../components/ui';
import { LoginForm } from '../features/user';
import { LOGIN_REDIRECT_PARAM, isLoginAfterPasswordReset, isLoginAfterSignup } from '../lib/routes';
import { useSession } from '../session/useSession';
import styles from './authCard.module.css';

const HOME_PATH = '/';

/**
 * 이 오리진 안의 경로일 때만 그대로, 그 밖(오픈 리다이렉트)은 홈으로.
 *
 * 문자열 접두 검사로 판정하지 않는다 — 브라우저의 URL 파서는 `\`를 `/`로 읽고 탭 · 줄바꿈을 지우므로 `/\evil.com`,
 * 탭이 낀 `/<TAB>/evil.com`이 `//evil.com`과 같은 곳으로 간다. 같은 파서(WHATWG URL)로 해석해 오리진이 바뀌는지 본다.
 */
function safeRedirect(value: string | null): string {
  if (!value || !value.startsWith('/')) return HOME_PATH;
  try {
    return new URL(value, window.location.origin).origin === window.location.origin ? value : HOME_PATH;
  } catch {
    return HOME_PATH;
  }
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
  const isSignedUp = isLoginAfterSignup(searchParams);
  const isPasswordReset = isLoginAfterPasswordReset(searchParams);

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
          <Alert variant="info" className={styles.notice}>
            가입이 완료되었습니다. 로그인해 주세요.
          </Alert>
        )}
        {isPasswordReset && (
          <Alert variant="info" className={styles.notice}>
            비밀번호를 바꿨습니다. 새 비밀번호로 로그인해 주세요.
          </Alert>
        )}
        <p className={`${styles.lead} type-body-strong`}>가입한 이메일과 비밀번호를 입력해 주세요.</p>
        <div className={styles.formSlot}>
          <LoginForm />
        </div>
        {/* 레이아웃 맵 7번 보조 링크 줄 — 링크 2개 + 가운데 | 구분자 */}
        <p className={`${styles.links} type-link`}>
          <Link to="/password-reset">비밀번호 찾기</Link>
          <span className={styles.separator} aria-hidden="true">
            |
          </span>
          <Link to="/signup">회원가입</Link>
        </p>
      </CardForm>
    </section>
  );
}
