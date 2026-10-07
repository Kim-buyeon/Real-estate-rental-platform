import { memo } from 'react';
import { Select, visuallyHiddenClassName } from '../../../components/ui';
import { SEOUL_DISTRICTS } from '../../../domain/property';
import styles from './DistrictPicker.module.css';

interface DistrictPickerProps {
  district: string | null;
  onSelect: (district: string) => void;
}

/**
 * 자치구를 이름으로 고르는 자리. 지도의 자치구 오버레이는 집계(PROP-08) 응답으로 그리므로
 * 집계가 비었거나 실패하면 지도 위에 단서가 남지 않는다. 그때도 2단계로 갈 수 있게 둔다.
 *
 * 필터 줄의 pulldown 처럼 라벨 없는 칸으로 보인다 — 「자치구」는 숨김 글자로 접근 이름만 맡는다.
 * 보이는 첫 항목이 「자치구」라 칸이 무엇인지는 화면에서도 읽힌다. 모양은 놓이는 줄이 입힌다.
 */
export const DistrictPicker = memo(function DistrictPicker({ district, onSelect }: DistrictPickerProps) {
  return (
    <label className={styles.picker}>
      <span className={visuallyHiddenClassName}>자치구</span>
      <Select
        value={district ?? ''}
        onChange={(event) => {
          if (event.target.value) onSelect(event.target.value);
        }}
      >
        <option value="">자치구</option>
        {SEOUL_DISTRICTS.map((name) => (
          <option key={name} value={name}>
            {name}
          </option>
        ))}
      </Select>
    </label>
  );
});
