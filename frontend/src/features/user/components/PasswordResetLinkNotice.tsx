import { Link } from 'react-router';
import { Alert, buttonClassName } from '../../../components/ui';
import styles from './PasswordResetLinkNotice.module.css';

interface PasswordResetLinkNoticeProps {
  /** 링크를 쓸 수 없는 이유. 무효 토큰이면 서버 error.message 그대로다 */
  message: string;
}

/**
 * 재설정 링크를 쓸 수 없을 때의 안내 (USER-06) — 무효 토큰(AUTH_RESET_TOKEN_INVALID)과 토큰 없이 들어온 경우가
 * 같은 모양이다. 고치는 길은 하나라 새 메일을 요청하는 화면으로 보낸다. 표현만 한다 — 훅을 부르지 않는다.
 */
export function PasswordResetLinkNotice({ message }: PasswordResetLinkNoticeProps) {
  return (
    <div className={styles.notice}>
      <Alert variant="error">{message}</Alert>
      <Link to="/password-reset" className={buttonClassName('primary')}>
        재설정 링크 다시 받기
      </Link>
    </div>
  );
}
