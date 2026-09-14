import { useState, type FormEvent } from 'react';
import type { LoginForm as LoginFormValues } from '../../../api/user';
import { Alert, Button, Field, Input } from '../../../components/ui';
import { EMAIL_MAX_LENGTH } from '../../../domain/user';
import { useLogin } from '../../../queries/user';
import styles from './LoginForm.module.css';

type FieldName = keyof LoginFormValues;

const FIELD_NAMES: readonly string[] = ['email', 'password'] satisfies FieldName[];

/**
 * 이메일 로그인 폼 (USER-02). 성공하면 세션이 시작되고, 이동은 페이지가 로그인 상태를 보고 한다.
 * 서버 error.field가 입력 이름이면 그 입력에, 아니면 폼 위 Alert에 message 그대로.
 */
export function LoginForm() {
  const [values, setValues] = useState<LoginFormValues>({ email: '', password: '' });
  const loginMutation = useLogin();
  const { error } = loginMutation;
  const fieldError = error?.field && FIELD_NAMES.includes(error.field) ? error : null;
  const formError = error && !fieldError ? error : null;

  const errorOf = (name: FieldName) => (fieldError?.field === name ? fieldError.message : null);

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    loginMutation.mutate(values);
  };

  return (
    <form className={styles.form} onSubmit={handleSubmit}>
      {formError && <Alert variant="error">{formError.message}</Alert>}
      <Field label="이메일" error={errorOf('email')}>
        {(control) => (
          <Input
            {...control}
            type="email"
            name="email"
            autoComplete="email"
            required
            maxLength={EMAIL_MAX_LENGTH}
            value={values.email}
            onChange={(event) => setValues((prev) => ({ ...prev, email: event.target.value }))}
          />
        )}
      </Field>
      <Field label="비밀번호" error={errorOf('password')}>
        {(control) => (
          <Input
            {...control}
            type="password"
            name="password"
            autoComplete="current-password"
            required
            value={values.password}
            onChange={(event) => setValues((prev) => ({ ...prev, password: event.target.value }))}
          />
        )}
      </Field>
      <Button type="submit" isLoading={loginMutation.isPending}>
        로그인
      </Button>
    </form>
  );
}
