import { useState, type FormEvent } from 'react';
import type { SignupForm as SignupFormValues } from '../../../api/user';
import { Alert, Button, Field, Input } from '../../../components/ui';
import { EMAIL_MAX_LENGTH, NAME_MAX_LENGTH, PHONE_MAX_LENGTH } from '../../../domain/user';
import { useSignup } from '../../../queries/user';
import styles from './authForm.module.css';

type FieldName = keyof SignupFormValues;

const FIELD_NAMES: readonly string[] = ['email', 'password', 'name', 'phone'] satisfies FieldName[];

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

  // 레이아웃 맵의 「비활성 주 버튼」 — 필수 입력(이메일 · 비밀번호 · 이름)이 비어 있으면 누를 수 없다.
  // 클라이언트 검증은 보조이고 판정은 서버가 한다 — 여기서 형식까지 보지 않는다
  const isSubmittable = Boolean(values.email.trim() && values.password && values.name.trim());

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const { phone, ...rest } = values;
    // 전화번호는 필수가 아니다. 비워 두면 보내지 않는다
    const form: SignupFormValues = phone.trim() ? { ...rest, phone } : rest;
    signupMutation.mutate(form, { onSuccess: onSignedUp });
  };

  return (
    <form className={styles.form} onSubmit={handleSubmit}>
      {formError && (
        <Alert variant="error" className={styles.alert}>
          {formError.message}
        </Alert>
      )}
      {/* field ↔ field 는 이 묶음의 gap 하나가 갖는다 — 주 버튼만 간격이 다르다 (레이아웃 맵) */}
      <div className={styles.fields}>
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
      </div>
      <Button
        type="submit"
        className={styles.submit}
        disabled={!isSubmittable}
        isLoading={signupMutation.isPending}
      >
        회원가입
      </Button>
    </form>
  );
}
