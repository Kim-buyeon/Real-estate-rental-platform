// ConsistencyCheck 검증 — 건축물대장에서 오는 정합 항목(주소 · 위반건축물 · 면적)이 null이면
// 「일치」 · 「해당 없음」이 아니라 확인 불가로 보이는지(이슈 308), 참 · 거짓 표기는 그대로인지 본다.
// 표현 컴포넌트라 쿼리 없이 props로 그린다. 문구는 domain/risk.ts 상수로 확인한다.
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CONSISTENCY_UNVERIFIABLE_LABEL, VIOLATION_BUILDING_UNVERIFIABLE_LABEL } from '../../../domain/risk';
import {
  CONSISTENCY_ALL_FAILED,
  CONSISTENCY_LEDGER_UNVERIFIED,
  RISK_ANALYSIS,
} from '../../../test/msw/handlers/risk';
import { ConsistencyCheck } from './ConsistencyCheck';

/** KvRow는 <dt>라벨</dt><dd>값</dd>이다 — 라벨 옆 값 칸의 글자 */
function valueOf(label: string) {
  return screen.getByText(label).nextElementSibling?.textContent;
}

describe('ConsistencyCheck', () => {
  it('모두 일치 · 위반 없음이면 기존대로 「일치」 · 「해당 없음」으로 보인다', () => {
    render(<ConsistencyCheck consistency={RISK_ANALYSIS.consistency} />);

    expect(valueOf('명의 일치')).toBe('일치');
    expect(valueOf('주소 일치')).toBe('일치');
    expect(valueOf('위반건축물')).toBe('해당 없음');
    expect(valueOf('면적 대조')).toBe('일치');
  });

  it('불일치 · 위반건축물 해당이면 기존대로 「불일치」 · 「해당」으로 보인다', () => {
    render(<ConsistencyCheck consistency={CONSISTENCY_ALL_FAILED} />);

    expect(valueOf('명의 일치')).toBe('불일치');
    expect(valueOf('주소 일치')).toBe('불일치');
    expect(valueOf('위반건축물')).toBe('해당');
    expect(valueOf('면적 대조')).toBe('불일치');
  });

  it('대장에서 오는 셋이 null이면 확인 불가로 보이고, 위반건축물은 대장 열람 안내까지 붙는다', () => {
    render(<ConsistencyCheck consistency={CONSISTENCY_LEDGER_UNVERIFIED} />);

    // 명의 일치는 등기에서 오므로 값이 있다
    expect(valueOf('명의 일치')).toBe('일치');
    expect(valueOf('주소 일치')).toBe(CONSISTENCY_UNVERIFIABLE_LABEL);
    expect(valueOf('위반건축물')).toBe(VIOLATION_BUILDING_UNVERIFIABLE_LABEL);
    expect(valueOf('면적 대조')).toBe(CONSISTENCY_UNVERIFIABLE_LABEL);
    expect(screen.queryByText('해당 없음')).not.toBeInTheDocument();
  });
});
