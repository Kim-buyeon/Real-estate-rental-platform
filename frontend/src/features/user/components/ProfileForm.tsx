import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import type { Profile, ProfileForm as ProfileFormValues } from '../../../api/user';
import { Alert, Button, Field, Input, Select } from '../../../components/ui';
import {
  CREDIT_SCORE_MAX,
  NAME_MAX_LENGTH,
  PHONE_MAX_LENGTH,
  PROFILE_FIELD_LABEL,
  PROFILE_FIELDS,
  profileFieldLabel,
  roleLabel,
} from '../../../domain/user';
import { formatDateTime } from '../../../lib/format';
import { userQueries, useUpdateProfile } from '../../../queries/user';
import styles from './ProfileForm.module.css';

/**
 * 입력 상태. 금액 · 점수를 문자열로 들고 있다 — 지우는 동안 빈 칸이 되어야 하는데 number로 두면
 * 지운 자리에 0이 들어간다. 제출 때 빈 칸을 0으로 바꾼다 (명세 1.1 「미입력 항목은 0 또는 null」).
 */
interface ProfileFields {
  name: string;
  phone: string;
  annualIncome: string;
  creditScore: string;
  existingLoan: string;
  existingLoanAnnualPayment: string;
  hasHouse: boolean;
  ownFund: string;
}

type FieldName = keyof ProfileFields;

// 자격 정보 여섯 필드의 정본은 domain/user.ts의 PROFILE_FIELDS다 — 여기에 다시 적으면 필드가 늘 때
// 한쪽만 고쳐지고, 빠진 필드의 서버 error.field가 입력이 아니라 폼 위 Alert로 떨어진다.
// satisfies는 PROFILE_FIELDS와 위 ProfileFields 인터페이스가 갈리는 것을 컴파일 시점에 잡는다 —
// 정본을 한쪽만 고치면 같은 증상이 되돌아오고 타입 검사도 테스트도 잡지 못한다
const FIELD_NAMES: readonly string[] = ['name', 'phone', ...PROFILE_FIELDS] satisfies FieldName[];

function toFields(profile: Profile): ProfileFields {
  const { account, profile: qualification } = profile;
  return {
    name: account.name,
    // 전화번호는 가입 때 비울 수 있어(백엔드 users.phone이 NULL 허용) 값이 없을 수 있다
    phone: account.phone ?? '',
    annualIncome: String(qualification.annualIncome),
    creditScore: String(qualification.creditScore),
    existingLoan: String(qualification.existingLoan),
    existingLoanAnnualPayment: String(qualification.existingLoanAnnualPayment),
    hasHouse: qualification.hasHouse,
    ownFund: String(qualification.ownFund),
  };
}

/** 빈 칸은 0이다. 필드를 빼지 않는다 — 수정 요청에는 수정 가능한 필드를 모두 담는다 (명세 1.1) */
function toAmount(value: string): number {
  const amount = Number(value);
  return value.trim() === '' || Number.isNaN(amount) ? 0 : amount;
}

function toRequest(fields: ProfileFields): ProfileFormValues {
  return {
    account: { name: fields.name, phone: fields.phone },
    profile: {
      annualIncome: toAmount(fields.annualIncome),
      creditScore: toAmount(fields.creditScore),
      existingLoan: toAmount(fields.existingLoan),
      existingLoanAnnualPayment: toAmount(fields.existingLoanAnnualPayment),
      hasHouse: fields.hasHouse,
      ownFund: toAmount(fields.ownFund),
    },
  };
}

/**
 * 서버 error.field로 입력을 고른다. 중첩 검증은 `profile.annualIncome` 꼴로 오고(백엔드가
 * 바인딩 경로를 그대로 담는다) 한도 계산의 422 PROFILE_INCOMPLETE는 `annualIncome` 꼴이라
 * 마지막 마디만 본다.
 */
function inputNameOf(field: string | undefined): string | null {
  if (!field) return null;
  return field.split('.').at(-1) ?? null;
}

/**
 * 계정 · 자격 정보 폼 (USER-03). 자격 정보는 대출 한도 계산의 입력이고, 미입력 여부는 서버가
 * 응답의 missingFields로 알려 준다 — 화면이 값을 보고 다시 판정하지 않는다 (명세 1.1).
 * 수정 불가 필드(이메일 · 권한 · 가입일시)는 보여주되 입력으로 두지 않는다 — 서버가 무시하므로
 * 입력을 열어 두면 고친 것처럼 보이고 반영되지 않는다.
 */
export function ProfileForm() {
  const profileQuery = useQuery(userQueries.profile());
  const updateMutation = useUpdateProfile();
  // 손대기 전에는 캐시가 그대로 출처다. 입력이 시작된 뒤에만 이 상태가 화면의 값이 되고, 저장에
  // 성공하면 다시 null로 돌아간다 — 응답을 계속 복사해 두면 저장 뒤 재조회한 값과 화면이 갈린다
  const [edited, setEdited] = useState<ProfileFields | null>(null);

  if (profileQuery.isPending) {
    return <p className="type-body">불러오는 중입니다.</p>;
  }

  if (profileQuery.error) {
    return <Alert variant="error">{profileQuery.error.message}</Alert>;
  }

  const profile = profileQuery.data;
  const fields = edited ?? toFields(profile);
  const { account, missingFields } = profile;

  const { error } = updateMutation;
  const errorInput = inputNameOf(error?.field);
  const fieldError = errorInput && FIELD_NAMES.includes(errorInput) ? error : null;
  const formError = error && !fieldError ? error : null;
  const errorOf = (name: FieldName) => (fieldError && errorInput === name ? fieldError.message : null);

  /**
   * 입력이 바뀔 때마다 부르는 한 자리. 초안을 갱신하면서 직전 저장 결과 안내를 지운다 — v5 뮤테이션의
   * isSuccess는 다음 mutate · reset까지 참이라, 저장 뒤 다시 입력하면 저장되지 않은 값 위에
   * 「저장했습니다」가 남는다.
   */
  const editFields = (next: ProfileFields) => {
    setEdited(next);
    if (updateMutation.isSuccess) updateMutation.reset();
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    // 성공에서 초안을 비워 화면의 출처를 캐시 하나로 되돌린다. 훅이 무효화를 기다린 뒤 성공을 내므로
    // (queries/user.ts) 이 시점의 캐시는 이미 재조회된 값이고, 옛 값이 한 번 스쳤다 바뀌지 않는다
    updateMutation.mutate(toRequest(fields), { onSuccess: () => setEdited(null) });
  };

  return (
    <form className={styles.form} onSubmit={handleSubmit}>
      {missingFields.length > 0 && (
        <Alert variant="info">
          <p className="type-body-strong">자격 정보가 미입력입니다</p>
          <ul className={styles.missing}>
            {missingFields.map((field) => (
              <li key={field}>{profileFieldLabel(field)}</li>
            ))}
          </ul>
          <p>미입력 상태에서는 대출 한도 계산과 계약 가능 매물 제시가 제한됩니다.</p>
        </Alert>
      )}

      {formError && <Alert variant="error">{formError.message}</Alert>}
      {/* 저장 결과는 폼 안의 안내다 — 뮤테이션 실패 표시의 Toast는 components/ui 어휘에 아직 없다 */}
      {updateMutation.isSuccess && <Alert variant="info">저장했습니다.</Alert>}

      <fieldset className={styles.group}>
        <legend className={`${styles.legend} type-label`}>계정 정보</legend>

        <Field label="이름" error={errorOf('name')}>
          {(control) => (
            <Input
              {...control}
              name="name"
              autoComplete="name"
              required
              maxLength={NAME_MAX_LENGTH}
              value={fields.name}
              onChange={(event) => editFields({ ...fields, name: event.target.value })}
            />
          )}
        </Field>

        <Field label="전화번호" error={errorOf('phone')}>
          {(control) => (
            <Input
              {...control}
              type="tel"
              name="phone"
              autoComplete="tel"
              maxLength={PHONE_MAX_LENGTH}
              value={fields.phone}
              onChange={(event) => editFields({ ...fields, phone: event.target.value })}
            />
          )}
        </Field>

        {/*
          수정 불가 — 이메일 변경은 별도 절차다 (명세 1.1). 숨기지 않고 읽기 전용으로 보여준다.
          행은 {components.info-row} 다 — 라벨 body-strong, 값 text-secondary
        */}
        <dl className={styles.facts}>
          <div className={styles.fact}>
            <dt className="type-body-strong">이메일</dt>
            <dd className="type-body">{account.email}</dd>
          </div>
          <div className={styles.fact}>
            <dt className="type-body-strong">권한</dt>
            <dd className="type-body">{roleLabel(account.role)}</dd>
          </div>
          <div className={styles.fact}>
            <dt className="type-body-strong">가입일시</dt>
            <dd className="type-body">{formatDateTime(account.createdAt)}</dd>
          </div>
        </dl>
      </fieldset>

      <fieldset className={styles.group}>
        <legend className={`${styles.legend} type-label`}>자격 정보</legend>

        <Field label={PROFILE_FIELD_LABEL.annualIncome} hint="원 단위" error={errorOf('annualIncome')}>
          {(control) => (
            <Input
              {...control}
              type="number"
              inputMode="numeric"
              name="annualIncome"
              min={0}
              value={fields.annualIncome}
              onChange={(event) => editFields({ ...fields, annualIncome: event.target.value })}
            />
          )}
        </Field>

        <Field label={PROFILE_FIELD_LABEL.creditScore} error={errorOf('creditScore')}>
          {(control) => (
            <Input
              {...control}
              type="number"
              inputMode="numeric"
              name="creditScore"
              min={0}
              max={CREDIT_SCORE_MAX}
              value={fields.creditScore}
              onChange={(event) => editFields({ ...fields, creditScore: event.target.value })}
            />
          )}
        </Field>

        <Field label={PROFILE_FIELD_LABEL.existingLoan} hint="원 단위" error={errorOf('existingLoan')}>
          {(control) => (
            <Input
              {...control}
              type="number"
              inputMode="numeric"
              name="existingLoan"
              min={0}
              value={fields.existingLoan}
              onChange={(event) => editFields({ ...fields, existingLoan: event.target.value })}
            />
          )}
        </Field>

        <Field
          label={PROFILE_FIELD_LABEL.existingLoanAnnualPayment}
          hint="원 단위"
          error={errorOf('existingLoanAnnualPayment')}
        >
          {(control) => (
            <Input
              {...control}
              type="number"
              inputMode="numeric"
              name="existingLoanAnnualPayment"
              min={0}
              value={fields.existingLoanAnnualPayment}
              onChange={(event) => editFields({ ...fields, existingLoanAnnualPayment: event.target.value })}
            />
          )}
        </Field>

        <Field label={PROFILE_FIELD_LABEL.hasHouse} error={errorOf('hasHouse')}>
          {(control) => (
            <Select
              {...control}
              name="hasHouse"
              value={String(fields.hasHouse)}
              onChange={(event) => editFields({ ...fields, hasHouse: event.target.value === 'true' })}
            >
              <option value="false">미보유</option>
              <option value="true">보유</option>
            </Select>
          )}
        </Field>

        <Field label={PROFILE_FIELD_LABEL.ownFund} hint="계약에 투입 가능한 금액 (원)" error={errorOf('ownFund')}>
          {(control) => (
            <Input
              {...control}
              type="number"
              inputMode="numeric"
              name="ownFund"
              min={0}
              value={fields.ownFund}
              onChange={(event) => editFields({ ...fields, ownFund: event.target.value })}
            />
          )}
        </Field>
      </fieldset>

      <Button type="submit" className={styles.submit} isLoading={updateMutation.isPending}>
        저장
      </Button>
    </form>
  );
}
