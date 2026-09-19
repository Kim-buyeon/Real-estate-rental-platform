import { useState, type FormEvent } from 'react';
import { Alert, Button, Field, Input } from '../../../components/ui';
import { EMAIL_MAX_LENGTH } from '../../../domain/user';
import { useRequestPasswordReset } from '../../../queries/user';
import styles from './authForm.module.css';

interface PasswordResetRequestFormProps {
  /** 요청을 마쳤다 — 가입 여부와 무관하게 온다. 안내 화면으로 바꾸는 것은 페이지가 한다 */
  onRequested: () => void;
}

/**
 * 비밀번호 재설정 메일 요청 폼 (USER-06). 서버가 응답한 결과는 모두 onRequested로 모인다 — 훅이 가입 여부를
 * 드러내는 실패를 성공으로 접는다(queries/user.ts). 이 폼에 남는 오류는 서버에 닿지 못한 경우 하나다.
 */
export function PasswordResetRequestForm({ onRequested }: PasswordResetRequestFormProps) {
  const [email, setEmail] = useState('');
  const requestMutation = useRequestPasswordReset();
  const { error } = requestMutation;

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    requestMutation.mutate(email, { onSuccess: onRequested });
  };

  return (
    <form className={styles.form} onSubmit={handleSubmit}>
      {error && (
        <Alert variant="error" className={styles.alert}>
          {error.message}
        </Alert>
      )}
      <Field label="이메일">
        {(control) => (
          <Input
            {...control}
            type="email"
            name="email"
            autoComplete="email"
            required
            maxLength={EMAIL_MAX_LENGTH}
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        )}
      </Field>
      <Button
        type="submit"
        className={styles.submit}
        disabled={!email.trim()}
        isLoading={requestMutation.isPending}
      >
        재설정 링크 받기
      </Button>
    </form>
  );
}
