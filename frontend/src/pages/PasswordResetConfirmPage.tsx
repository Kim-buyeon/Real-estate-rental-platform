import { useCallback, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { CardForm } from '../components/ui';
import { PasswordResetConfirmForm, PasswordResetLinkNotice } from '../features/user';
import { loginAfterPasswordResetPath, readPasswordResetToken } from '../lib/routes';
import styles from './authCard.module.css';

/**
 * 토큰 없이 들어온 경우의 안내. 서버를 부르지 않고 정해지는 상태라 서버 문구가 없다 — 무효 토큰과 같은 모양으로 둔다
 */
const MISSING_TOKEN_MESSAGE = '비밀번호 재설정 링크가 올바르지 않습니다. 메일의 링크로 다시 들어오거나 새로 요청해 주세요.';

/**
 * `/password-reset/confirm?token=` — USER-06 새 비밀번호 확정. 토큰은 쿼리에서 읽어 요청 본문으로만 보낸다 —
 * 화면에 그리지 않는다. 성공하면 자동 로그인 없이 로그인 화면으로 replace 이동한다 — 뒤로 가기로 쓴 링크에 돌아오지 않게.
 * 토큰이 없을 때와 서버가 무효라고 답했을 때는 같은 모양이다 — 안내문 · 폼 자리에 다시 요청 안내 하나만 남는다.
 *
 * 카드 규격 · 여백은 레이아웃 맵 design/examples/login/layout.md 를 따른다 — 입력 둘 · 주 버튼 · 보조 링크 줄의 같은 골격이다.
 */
export default function PasswordResetConfirmPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const token = readPasswordResetToken(searchParams);
  /** 서버가 토큰을 무효로 답한 문구. 쿼리의 토큰이 바뀌면(다른 메일 링크) 새 토큰으로 다시 시도할 수 있게 비운다 */
  const [invalidToken, setInvalidToken] = useState<{ token: string; message: string } | null>(null);
  const invalidMessage = invalidToken && invalidToken.token === token ? invalidToken.message : null;
  const noticeMessage = token === null ? MISSING_TOKEN_MESSAGE : invalidMessage;

  const handleReset = useCallback(() => {
    // 재설정은 로그인을 대신하지 않는다(명세 1.3) — 로그인 화면으로 보내 안내한다
    void navigate(loginAfterPasswordResetPath(), { replace: true });
  }, [navigate]);

  return (
    <section className={styles.page}>
      <CardForm>
        <h1 className="type-heading-1">비밀번호 재설정</h1>
        <hr className={styles.divider} />
        {token === null || noticeMessage !== null ? (
          <div className={styles.notice}>
            <PasswordResetLinkNotice message={noticeMessage ?? MISSING_TOKEN_MESSAGE} />
          </div>
        ) : (
          <>
            <p className={`${styles.lead} type-body-strong`}>새로 쓸 비밀번호를 입력해 주세요.</p>
            <div className={styles.formSlot}>
              <PasswordResetConfirmForm
                token={token}
                onReset={handleReset}
                onTokenInvalid={(message) => setInvalidToken({ token, message })}
              />
            </div>
          </>
        )}
        <p className={`${styles.links} type-link`}>
          <Link to="/login">로그인으로 돌아가기</Link>
        </p>
      </CardForm>
    </section>
  );
}
