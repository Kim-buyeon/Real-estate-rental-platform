import { useState, type FormEvent } from 'react';
import type { SignupForm as SignupFormValues } from '../../../api/user';
import { Alert, Button, Field, Input } from '../../../components/ui';
import { useSignup } from '../../../queries/user';
import styles from './SignupForm.module.css';

type FieldName = keyof SignupFormValues;

const FIELD_NAMES: readonly string[] = ['email', 'password', 'name', 'phone'] satisfies FieldName[];

/** 백엔드 SignupRequest 검증 길이 */
const EMAIL_MAX_LENGTH = 100;
const NAME_MAX_LENGTH = 50;
const PHONE_MAX_LENGTH = 20;

interface SignupFormProps {
  /** 가입 성공 — 이동은 페이지가 정한다 */
  onSignedUp: () => void;
}

/**
 * 이메일 가입 폼 (USER-01). 비밀번호 규칙은 서버가 판정한다 — 코드에만 있는 규칙을 만들지 않는다.
 * 서버 error.field가 입력 이름이면 그 입력에, 아니면 폼 위 Alert에 message 그대로.
 */
export function SignupForm({ onSignedUp }: SignupFormProps) {
  const [values, setValues] = useState<Required<SignupFormValues>>({ email: '', password: '', name: '', phone: '' });
  const signupMutation = useSignup();
  const { error } = signupMutation;
  const fieldError = error?.field && FIELD_NAMES.includes(error.field) ? error : null;
  const formError = error && !fieldError ? error : null;

  const errorOf = (name: FieldName) => (fieldError?.field === name ? fieldError.message : null);

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const { phone, ...rest } = values;
    // 전화번호는 필수가 아니다. 비워 두면 보내지 않는다
    const form: SignupFormValues = phone.trim() ? { ...rest, phone } : rest;
    signupMutation.mutate(form, { onSuccess: onSignedUp });
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
            autoComplete="new-password"
            required
            value={values.password}
            onChange={(event) => setValues((prev) => ({ ...prev, password: event.target.value }))}
          />
        )}
      </Field>
      <Field label="이름" error={errorOf('name')}>
        {(control) => (
          <Input
            {...control}
            name="name"
            autoComplete="name"
            required
            maxLength={NAME_MAX_LENGTH}
            value={values.name}
            onChange={(event) => setValues((prev) => ({ ...prev, name: event.target.value }))}
          />
        )}
      </Field>
      <Field label="전화번호 (선택)" error={errorOf('phone')}>
        {(control) => (
          <Input
            {...control}
            type="tel"
            name="phone"
            autoComplete="tel"
            maxLength={PHONE_MAX_LENGTH}
            value={values.phone}
            onChange={(event) => setValues((prev) => ({ ...prev, phone: event.target.value }))}
          />
        )}
      </Field>
      <Button type="submit" isLoading={signupMutation.isPending}>
        회원가입
      </Button>
    </form>
  );
}
