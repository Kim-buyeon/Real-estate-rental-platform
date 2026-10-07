import { memo, useCallback, type ReactNode } from 'react';
import type { PropertyFilter } from '../../../api/property';
import { Button, Field, Select } from '../../../components/ui';
import {
  CONTRACT_TYPES,
  DEPOSIT_MAX_OPTIONS,
  contractTypeLabel,
  type ContractType,
} from '../../../domain/property';
import { RISK_GRADES, RISK_GRADE_LABEL, type RiskGrade } from '../../../domain/risk';
import { formatDepositShort } from '../../../lib/format';
import styles from './PropertyFilterBar.module.css';

interface PropertyFilterBarProps {
  filter: PropertyFilter;
  onChange: (filter: PropertyFilter) => void;
  /**
   * 필터 앞쪽에 같은 줄로 놓을 것. 지도 화면이 단계 이동(「← 서울 전체」 · 자치구 선택)을 넣는다 —
   * 상단 띠를 한 줄로 두려고 자리만 빌려 준다. 상태는 넣는 쪽이 갖고, 필터 상태와 섞이지 않는다.
   */
  leading?: ReactNode;
}

/** 필터 줄이 다루는 값. 초기화는 이 값들만 되돌리고 나머지 키는 그대로 둔다 */
const FILTER_BAR_KEYS = ['contractType', 'depositMax', 'riskGrade'] as const satisfies readonly (keyof PropertyFilter)[];

/** 필터 줄이 다루는 값을 기본값(키 없음 — 지도 화면의 첫 필터가 빈 객체다)으로 되돌린 필터 */
const resetFilterBarValues = (filter: PropertyFilter): PropertyFilter => {
  const next: PropertyFilter = { ...filter };
  for (const key of FILTER_BAR_KEYS) delete next[key];
  return next;
};

/** 필터 줄이 다루는 값이 모두 기본값인가 — 초기화 버튼의 비활성 판정. 위험 등급 빈 배열은 선택 없음과 같다 */
const isFilterBarInitial = (filter: PropertyFilter): boolean =>
  filter.contractType === undefined && filter.depositMax === undefined && (filter.riskGrade ?? []).length === 0;

/**
 * 공통 검색 필터 — 매물 API 명세 1.1. 자치구 집계와 매물 조회가 같은 조건을 쓰므로
 * 단계를 오가도 그대로 유지된다. 필터 상태는 페이지가 소유한다.
 */
export const PropertyFilterBar = memo(function PropertyFilterBar({ filter, onChange, leading }: PropertyFilterBarProps) {
  const toggleGrade = useCallback(
    (grade: RiskGrade) => {
      const selected = filter.riskGrade ?? [];
      const next = selected.includes(grade) ? selected.filter((item) => item !== grade) : [...selected, grade];
      onChange({ ...filter, riskGrade: next.length > 0 ? next : undefined });
    },
    [filter, onChange],
  );

  const handleReset = useCallback(() => onChange(resetFilterBarValues(filter)), [filter, onChange]);
  const isInitial = isFilterBarInitial(filter);

  return (
    <div className={styles.bar} role="search" aria-label="매물 필터">
      {leading !== undefined && <div className={styles.leading}>{leading}</div>}

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
                {contractTypeLabel(type)}
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

      {/* 레이아웃 맵 map-search filter-bar 끝의 초기화 버튼. 자치구 단계 · 지도 위치는 필터가 아니라 건드리지 않는다 */}
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className={styles.reset}
        aria-label="필터 초기화"
        disabled={isInitial}
        onClick={handleReset}
      >
        <svg className={styles.resetIcon} viewBox="0 0 16 16" aria-hidden="true">
          <path d="M3 8a5 5 0 1 0 1.5-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          <polyline points="3,2 3,5 6,5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
        </svg>
        초기화
      </Button>
    </div>
  );
});
