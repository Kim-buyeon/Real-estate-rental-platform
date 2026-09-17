import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import type { NewPropertyConditions, NotificationSubscriptions } from '../../../api/notification';
import { Alert, Button, Field, Input, Select } from '../../../components/ui';
import {
  SUBSCRIPTION_ENABLED_LABEL,
  SUBSCRIPTION_ITEM_HINT,
  SUBSCRIPTION_ITEM_LABEL,
} from '../../../domain/notification';
import { CONTRACT_TYPES, CONTRACT_TYPE_LABEL, SEOUL_DISTRICTS, type ContractType } from '../../../domain/property';
import { formatWon } from '../../../lib/format';
import { notificationQueries, useUpdateNotificationSubscriptions } from '../../../queries/notification';
import styles from './SubscriptionForm.module.css';

/**
 * 입력 상태. 네 항목의 수신 여부와 신규 매물 조건을 한 겹으로 편다 — 조건은 신규 매물만 갖는다 (명세 1.2).
 * depositMax를 문자열로 들고 있는 이유는 ProfileForm과 같다 — 지우는 동안 빈 칸이어야 하는데 number로
 * 두면 지운 자리에 0이 들어간다. 여기서 빈 칸은 0이 아니라 「상한 없음」이라 제출 때 필드를 뺀다.
 * contractType의 빈 문자열도 값이 아니라 「계약 유형 전체」다.
 */
interface SubscriptionFields {
  newPropertyEnabled: boolean;
  districts: string[];
  contractType: ContractType | '';
  depositMax: string;
  rateChangeEnabled: boolean;
  wishlistMonitoringEnabled: boolean;
  consultScheduleEnabled: boolean;
}

/** 서버 error.field의 마지막 마디와 맞춰 볼 입력 이름. 조건 셋이 전부다 — 아래 inputNameOf 주석 참고 */
const FIELD_NAMES: readonly string[] = ['districts', 'contractType', 'depositMax'];

/** 열거에 없는 값을 보내지 않는다. Select의 선택지가 CONTRACT_TYPES라 빈 문자열 외에는 걸리지 않는다 */
const toContractType = (value: string): ContractType | '' =>
  (CONTRACT_TYPES as readonly string[]).includes(value) ? (value as ContractType) : '';

function toFields(subscriptions: NotificationSubscriptions): SubscriptionFields {
  const { newProperty, rateChange, wishlistMonitoring, consultSchedule } = subscriptions;
  const { conditions } = newProperty;
  return {
    // enabled가 false여도 저장된 조건을 그대로 싣는다 — 조회가 활성 여부와 무관하게 조건을 돌려주므로
    // (명세 1.2) 여기서 버리면 다시 켤 때 화면이 조건을 잃는다
    newPropertyEnabled: newProperty.enabled,
    districts: conditions.districts,
    contractType: toContractType(conditions.contractType ?? ''),
    depositMax: conditions.depositMax == null ? '' : String(conditions.depositMax),
    rateChangeEnabled: rateChange.enabled,
    wishlistMonitoringEnabled: wishlistMonitoring.enabled,
    consultScheduleEnabled: consultSchedule.enabled,
  };
}

/**
 * 수정 요청은 조회 응답과 동일한 구조로 **전체**를 전달한다 (명세 1.2). 바뀐 항목만 보내지 않는다.
 *
 * 조건은 newPropertyEnabled와 무관하게 항상 담는다 — 명세가 「enabled가 false인데 조건이 오면 조건을
 * 보존한다」로 정하므로, 꺼진 채 저장해도 조건이 서버에 남고 다시 켤 때 그대로 돌아온다.
 * 조건을 비우는 것은 사용자가 자치구 체크를 지우는 동작이지 토글을 끄는 동작이 아니다.
 */
function toRequest(fields: SubscriptionFields): NotificationSubscriptions {
  const conditions: NewPropertyConditions = { districts: fields.districts };
  if (fields.contractType) conditions.contractType = fields.contractType;
  const depositMax = Number(fields.depositMax);
  if (fields.depositMax.trim() !== '' && !Number.isNaN(depositMax)) conditions.depositMax = depositMax;

  return {
    newProperty: { enabled: fields.newPropertyEnabled, conditions },
    rateChange: { enabled: fields.rateChangeEnabled },
    wishlistMonitoring: { enabled: fields.wishlistMonitoringEnabled },
    consultSchedule: { enabled: fields.consultScheduleEnabled },
  };
}

/**
 * 서버 error.field로 입력을 고른다. 구독 설정의 필드 경로는 `newProperty.conditions.districts`처럼
 * 중첩돼 있어 ProfileForm과 같이 마지막 마디만 본다. 마지막 마디가 `enabled`인 오류(항목 누락)는
 * 네 항목 중 어느 것인지 이름만으로 가릴 수 없어 폼 위 Alert로 떨어진다 — 그 오류는 화면이 네 항목을
 * 전부 담아 보내는 한 나지 않는다.
 */
function inputNameOf(field: string | undefined): string | null {
  if (!field) return null;
  return field.split('.').at(-1) ?? null;
}

/**
 * 알림 구독 설정 폼 (NOTI-01). 네 항목의 수신 여부와 신규 매물 조건을 다룬다.
 *
 * **자치구 필수 여부를 화면이 판정하지 않는다.** 「enabled가 true면 districts 1개 이상」은 서버가
 * 400 INVALID_REQUEST + error.field로 알려 주는 규칙이고, 화면이 먼저 막으면 같은 규칙이 두 곳에 생긴다.
 */
export function SubscriptionForm() {
  const subscriptionsQuery = useQuery(notificationQueries.subscriptions());
  const updateMutation = useUpdateNotificationSubscriptions();
  // 손대기 전에는 캐시가 그대로 출처다. 입력이 시작된 뒤에만 이 상태가 화면의 값이 되고, 저장에
  // 성공하면 다시 null로 돌아간다 — 응답을 계속 복사해 두면 저장 뒤 재조회한 값과 화면이 갈린다
  const [edited, setEdited] = useState<SubscriptionFields | null>(null);

  if (subscriptionsQuery.isPending) {
    return <p className="type-body">불러오는 중입니다.</p>;
  }

  if (subscriptionsQuery.error) {
    return <Alert variant="error">{subscriptionsQuery.error.message}</Alert>;
  }

  const fields = edited ?? toFields(subscriptionsQuery.data);

  const { error } = updateMutation;
  const errorInput = inputNameOf(error?.field);
  const fieldError = errorInput && FIELD_NAMES.includes(errorInput) ? error : null;
  const formError = error && !fieldError ? error : null;
  const errorOf = (name: string) => (fieldError && errorInput === name ? fieldError.message : null);

  /** 입력이 바뀔 때마다 부르는 한 자리. 직전 저장 결과 안내를 지운다 — ProfileForm과 같은 이유다 */
  const editFields = (next: SubscriptionFields) => {
    setEdited(next);
    if (updateMutation.isSuccess) updateMutation.reset();
  };

  const toggleDistrict = (name: string, isChecked: boolean) =>
    editFields({
      ...fields,
      districts: isChecked
        ? [...fields.districts, name]
        : fields.districts.filter((district) => district !== name),
    });

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    // 성공에서 초안을 비워 화면의 출처를 캐시 하나로 되돌린다. 훅이 무효화를 기다린 뒤 성공을 내므로
    // (queries/notification.ts) 이 시점의 캐시는 이미 재조회된 값이다
    updateMutation.mutate(toRequest(fields), { onSuccess: () => setEdited(null) });
  };

  const depositMaxAmount = Number(fields.depositMax);
  const depositMaxHint =
    fields.depositMax.trim() === '' || Number.isNaN(depositMaxAmount)
      ? '원 단위 · 비우면 상한 없음'
      : `${formatWon(depositMaxAmount)} 이하`;

  return (
    <form className={styles.form} onSubmit={handleSubmit}>
      {formError && <Alert variant="error">{formError.message}</Alert>}
      {/* 저장 결과는 폼 안의 안내다 — 뮤테이션 실패 표시의 Toast는 components/ui 어휘에 아직 없다 */}
      {updateMutation.isSuccess && <Alert variant="info">저장했습니다.</Alert>}

      <fieldset className={styles.group}>
        <legend className={`${styles.legend} type-label`}>{SUBSCRIPTION_ITEM_LABEL.newProperty}</legend>

        <Field label="수신 여부" hint={SUBSCRIPTION_ITEM_HINT.newProperty}>
          {(control) => (
            <Select
              {...control}
              name="newPropertyEnabled"
              value={String(fields.newPropertyEnabled)}
              onChange={(event) => editFields({ ...fields, newPropertyEnabled: event.target.value === 'true' })}
            >
              <option value="false">{SUBSCRIPTION_ENABLED_LABEL.false}</option>
              <option value="true">{SUBSCRIPTION_ENABLED_LABEL.true}</option>
            </Select>
          )}
        </Field>

        {/* 수신을 꺼도 조건 입력을 감추거나 비우지 않는다 — 조건은 보존되고 다시 켤 때 그대로 쓰인다 */}
        <Field label="자치구" hint="신규 매물을 받을 자치구를 고릅니다" error={errorOf('districts')}>
          {(control) => (
            // 체크박스 묶음이라 Field가 준 id · aria 속성을 묶음 자체에 건다 — components/ui에 Checkbox가
            // 아직 없어 네이티브 입력을 쓴다 (새 공용 컴포넌트는 디자인 토큰 정의서의 작업이다)
            <div {...control} role="group" aria-label="자치구" className={styles.districts}>
              {SEOUL_DISTRICTS.map((name) => (
                <label key={name} className={`${styles.district} type-body`}>
                  <input
                    type="checkbox"
                    name="districts"
                    value={name}
                    checked={fields.districts.includes(name)}
                    onChange={(event) => toggleDistrict(name, event.target.checked)}
                  />
                  {name}
                </label>
              ))}
            </div>
          )}
        </Field>

        <Field label="계약 유형" hint="고르지 않으면 계약 유형 전체" error={errorOf('contractType')}>
          {(control) => (
            <Select
              {...control}
              name="contractType"
              value={fields.contractType}
              onChange={(event) => editFields({ ...fields, contractType: toContractType(event.target.value) })}
            >
              <option value="">전체</option>
              {CONTRACT_TYPES.map((contractType) => (
                <option key={contractType} value={contractType}>
                  {CONTRACT_TYPE_LABEL[contractType]}
                </option>
              ))}
            </Select>
          )}
        </Field>

        <Field label="보증금 상한" hint={depositMaxHint} error={errorOf('depositMax')}>
          {(control) => (
            <Input
              {...control}
              type="number"
              inputMode="numeric"
              name="depositMax"
              min={0}
              value={fields.depositMax}
              onChange={(event) => editFields({ ...fields, depositMax: event.target.value })}
            />
          )}
        </Field>
      </fieldset>

      <fieldset className={styles.group}>
        <legend className={`${styles.legend} type-label`}>수신 여부만 정하는 알림</legend>

        <Field label={SUBSCRIPTION_ITEM_LABEL.rateChange} hint={SUBSCRIPTION_ITEM_HINT.rateChange}>
          {(control) => (
            <Select
              {...control}
              name="rateChangeEnabled"
              value={String(fields.rateChangeEnabled)}
              onChange={(event) => editFields({ ...fields, rateChangeEnabled: event.target.value === 'true' })}
            >
              <option value="false">{SUBSCRIPTION_ENABLED_LABEL.false}</option>
              <option value="true">{SUBSCRIPTION_ENABLED_LABEL.true}</option>
            </Select>
          )}
        </Field>

        {/* 켜고 끄는 범위가 이 화면 밖까지 미친다 — 안내 문구는 domain의 매핑이 갖는다 (명세 1.2) */}
        <Field label={SUBSCRIPTION_ITEM_LABEL.wishlistMonitoring} hint={SUBSCRIPTION_ITEM_HINT.wishlistMonitoring}>
          {(control) => (
            <Select
              {...control}
              name="wishlistMonitoringEnabled"
              value={String(fields.wishlistMonitoringEnabled)}
              onChange={(event) =>
                editFields({ ...fields, wishlistMonitoringEnabled: event.target.value === 'true' })
              }
            >
              <option value="false">{SUBSCRIPTION_ENABLED_LABEL.false}</option>
              <option value="true">{SUBSCRIPTION_ENABLED_LABEL.true}</option>
            </Select>
          )}
        </Field>

        <Field label={SUBSCRIPTION_ITEM_LABEL.consultSchedule} hint={SUBSCRIPTION_ITEM_HINT.consultSchedule}>
          {(control) => (
            <Select
              {...control}
              name="consultScheduleEnabled"
              value={String(fields.consultScheduleEnabled)}
              onChange={(event) => editFields({ ...fields, consultScheduleEnabled: event.target.value === 'true' })}
            >
              <option value="false">{SUBSCRIPTION_ENABLED_LABEL.false}</option>
              <option value="true">{SUBSCRIPTION_ENABLED_LABEL.true}</option>
            </Select>
          )}
        </Field>
      </fieldset>

      <Button type="submit" isLoading={updateMutation.isPending}>
        저장
      </Button>
    </form>
  );
}
