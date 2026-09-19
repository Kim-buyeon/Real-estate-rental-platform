import { useCallback, useState } from 'react';
import { Link } from 'react-router';
import { Alert, CardForm } from '../components/ui';
import { PasswordResetRequestForm } from '../features/user';
import styles from './authCard.module.css';

/**
 * `/password-reset` — USER-06 재설정 메일 요청. 제출 뒤에는 결과와 무관하게 같은 안내로 바뀐다 —
 * 가입되지 않은 이메일도 같은 화면이어야 응답으로 가입 여부를 알 수 없다(명세 1.3). 무엇을 같은 결과로 보는지는
 * 훅(useRequestPasswordReset)이 정하고, 이 화면은 요청을 마쳤는지만 안다.
 *
 * 카드 규격 · 여백은 레이아웃 맵 design/examples/login/layout.md 를 따른다 — 입력이 하나인 같은 골격이다.
 */
export default function PasswordResetRequestPage() {
  const [isRequested, setIsRequested] = useState(false);
  const handleRequested = useCallback(() => setIsRequested(true), []);

  return (
    <section className={styles.page}>
      <CardForm>
        <h1 className="type-heading-1">비밀번호 찾기</h1>
        <hr className={styles.divider} />
        {isRequested ? (
          <Alert variant="info" className={styles.notice}>
            입력한 이메일로 가입한 계정이 있으면 비밀번호 재설정 링크를 보냈습니다. 메일의 링크에서 새 비밀번호를
            정해 주세요. 메일이 오지 않으면 주소를 확인하고 잠시 뒤 다시 요청해 주세요.
          </Alert>
        ) : (
          <>
            <p className={`${styles.lead} type-body-strong`}>
              가입한 이메일을 입력하면 비밀번호를 다시 정하는 링크를 보내 드립니다.
            </p>
            <div className={styles.formSlot}>
              <PasswordResetRequestForm onRequested={handleRequested} />
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
