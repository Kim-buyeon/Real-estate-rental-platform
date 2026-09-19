import { useState, type FormEvent } from 'react';
import { Alert, Button, Field, Input } from '../../../components/ui';
import { useConfirmPasswordReset } from '../../../queries/user';
import styles from './authForm.module.css';

/** 없는 · 만료된 · 이미 쓴 · 대체된 토큰 — 명세 1.3은 사유를 가르지 않는다 */
const AUTH_RESET_TOKEN_INVALID = 'AUTH_RESET_TOKEN_INVALID';

/** 규칙 위반이 오는 필드 — 명세 1.3 「오류 봉투의 field에 newPassword」 */
const NEW_PASSWORD_FIELD = 'newPassword';

/** 두 입력이 다를 때의 문구. 서버에 보내지 않는 확인 입력이라 서버 문구가 없다 — 프론트가 갖는다 */
const MISMATCH_MESSAGE = '새 비밀번호가 서로 다릅니다. 같은 비밀번호를 두 번 입력해 주세요.';

interface PasswordResetConfirmFormProps {
  /** 메일 링크의 쿼리에서 읽은 토큰. 본문으로만 보내고 화면에 그리지 않는다 */
  token: string;
  /** 비밀번호를 바꿨다 — 이동은 페이지가 정한다 */
  onReset: () => void;
  /**
   * 토큰이 무효다(AUTH_RESET_TOKEN_INVALID) — 인자는 서버 error.message 그대로. 같은 토큰으로는 다시 보내도 결과가
   * 같아 폼을 둘 이유가 없다. 폼 · 안내문을 거두고 토큰 없음과 같은 안내로 바꾸는 것은 페이지가 한다
   */
  onTokenInvalid: (message: string) => void;
}

/**
 * 새 비밀번호 확정 폼 (USER-06). 비밀번호 규칙은 가입과 같이 서버가 판정한다 — 코드에만 있는 규칙을 만들지 않는다.
 * 무효 토큰은 onTokenInvalid로 넘긴다 — 이 폼은 그 오류를 그리지 않는다.
 * 서버 error.field가 newPassword면 그 입력에, 아니면 폼 위 Alert에 message 그대로.
 */
export function PasswordResetConfirmForm({ token, onReset, onTokenInvalid }: PasswordResetConfirmFormProps) {
  const [newPassword, setNewPassword] = useState('');
  const [newPasswordConfirm, setNewPasswordConfirm] = useState('');
  const [isMismatched, setIsMismatched] = useState(false);
  const confirmMutation = useConfirmPasswordReset();
  const { error } = confirmMutation;

  // 무효 토큰은 페이지가 폼을 거두며 안내한다 — 거두기 전 한 번의 렌더에 폼 위 Alert로 겹쳐 그리지 않는다
  const shownError = error?.code === AUTH_RESET_TOKEN_INVALID ? null : error;
  const fieldError = shownError?.field === NEW_PASSWORD_FIELD ? shownError : null;
  const formError = shownError && !fieldError ? shownError : null;

  // 가입 폼과 같은 보조 검증 — 비어 있으면 누를 수 없다. 형식은 서버가 판정한다
  const isSubmittable = Boolean(newPassword && newPasswordConfirm);

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (newPassword !== newPasswordConfirm) {
      setIsMismatched(true);
      return;
    }
    confirmMutation.mutate(
      { token, newPassword },
      {
        onSuccess: onReset,
        onError: (mutationError) => {
          if (mutationError.code === AUTH_RESET_TOKEN_INVALID) onTokenInvalid(mutationError.message);
        },
      },
    );
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
        <Field label="새 비밀번호" error={fieldError?.message ?? null}>
          {(control) => (
            <Input
              {...control}
              type="password"
              name="newPassword"
              autoComplete="new-password"
              required
              value={newPassword}
              onChange={(event) => {
                setNewPassword(event.target.value);
                setIsMismatched(false);
              }}
            />
          )}
        </Field>
        <Field label="새 비밀번호 확인" error={isMismatched ? MISMATCH_MESSAGE : null}>
          {(control) => (
            <Input
              {...control}
              type="password"
              name="newPasswordConfirm"
              autoComplete="new-password"
              required
              value={newPasswordConfirm}
              onChange={(event) => {
                setNewPasswordConfirm(event.target.value);
                setIsMismatched(false);
              }}
            />
          )}
        </Field>
      </div>
      <Button
        type="submit"
        className={styles.submit}
        disabled={!isSubmittable}
        isLoading={confirmMutation.isPending}
      >
        비밀번호 변경
      </Button>
    </form>
  );
}
