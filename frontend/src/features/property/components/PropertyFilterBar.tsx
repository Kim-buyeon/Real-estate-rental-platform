import { memo, useCallback } from 'react';
import type { PropertyFilter } from '../../../api/property';
import { Button, Field, Select } from '../../../components/ui';
import { CONTRACT_TYPES, CONTRACT_TYPE_LABEL, DEPOSIT_MAX_OPTIONS, type ContractType } from '../../../domain/property';
import { RISK_GRADES, RISK_GRADE_LABEL, type RiskGrade } from '../../../domain/risk';
import { formatDepositShort } from '../../../lib/format';
import styles from './PropertyFilterBar.module.css';

interface PropertyFilterBarProps {
  filter: PropertyFilter;
  onChange: (filter: PropertyFilter) => void;
}

/**
 * 공통 검색 필터 — 매물 API 명세 1.1. 자치구 집계와 매물 조회가 같은 조건을 쓰므로
 * 단계를 오가도 그대로 유지된다. 필터 상태는 페이지가 소유한다.
 */
export const PropertyFilterBar = memo(function PropertyFilterBar({ filter, onChange }: PropertyFilterBarProps) {
  const toggleGrade = useCallback(
    (grade: RiskGrade) => {
      const selected = filter.riskGrade ?? [];
      const next = selected.includes(grade) ? selected.filter((item) => item !== grade) : [...selected, grade];
      onChange({ ...filter, riskGrade: next.length > 0 ? next : undefined });
    },
    [filter, onChange],
  );

  return (
    <div className={styles.bar}>
      <Field label="계약유형">
        {(control) => (
          <Select
            {...control}
            className={styles.pulldown}
            value={filter.contractType ?? ''}
            onChange={(event) =>
              onChange({ ...filter, contractType: (event.target.value || undefined) as ContractType | undefined })
            }
          >
            <option value="">전체</option>
            {CONTRACT_TYPES.map((type) => (
              <option key={type} value={type}>
                {CONTRACT_TYPE_LABEL[type]}
              </option>
            ))}
          </Select>
        )}
      </Field>

      <Field label="보증금 상한">
        {(control) => (
          <Select
            {...control}
            className={styles.pulldown}
            value={filter.depositMax ?? ''}
            onChange={(event) =>
              onChange({ ...filter, depositMax: event.target.value ? Number(event.target.value) : undefined })
            }
          >
            <option value="">전체</option>
            {DEPOSIT_MAX_OPTIONS.map((amount) => (
              <option key={amount} value={amount}>
                {formatDepositShort(amount)} 이하
              </option>
            ))}
          </Select>
        )}
      </Field>

      <fieldset className={styles.grades}>
        <legend className={`${styles.legend} type-label`}>위험 등급</legend>
        <div className={styles.gradeButtons}>
          {RISK_GRADES.map((grade) => {
            const isSelected = (filter.riskGrade ?? []).includes(grade);
            return (
              <Button
                key={grade}
                type="button"
                size="sm"
                variant={isSelected ? 'primary' : 'secondary'}
                aria-pressed={isSelected}
                onClick={() => toggleGrade(grade)}
              >
                {RISK_GRADE_LABEL[grade]}
              </Button>
            );
          })}
        </div>
      </fieldset>
    </div>
  );
});
