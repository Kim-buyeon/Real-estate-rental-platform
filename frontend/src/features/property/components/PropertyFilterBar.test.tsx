// PropertyFilterBar 필터 조합 로직 검증 — 계약유형 select와 위험 등급 토글이 onChange에
// 넘기는 필터 모양을 확인한다. userEvent는 devDependencies에 없어 fireEvent를 쓴다.
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { PropertyFilter } from '../../../api/property';
import { PropertyFilterBar } from './PropertyFilterBar';

describe('PropertyFilterBar', () => {
  it('계약유형 select를 바꾸면 onChange가 contractType을 담아 불린다', () => {
    const handleChange = vi.fn();
    render(<PropertyFilterBar filter={{}} onChange={handleChange} />);

    fireEvent.change(screen.getByLabelText('계약유형'), { target: { value: 'DEPOSIT_ONLY' } });

    expect(handleChange).toHaveBeenCalledWith({ contractType: 'DEPOSIT_ONLY' });
  });

  it('위험 등급 버튼을 누르면 그 등급이 riskGrade 배열로 담긴다', () => {
    const handleChange = vi.fn();
    render(<PropertyFilterBar filter={{}} onChange={handleChange} />);

    fireEvent.click(screen.getByRole('button', { name: '안전' }));

    expect(handleChange).toHaveBeenCalledWith({ riskGrade: ['SAFE'] });
  });

  it('선택된 위험 등급 버튼을 다시 누르면 riskGrade가 undefined로 빠진다', () => {
    const handleChange = vi.fn();
    const filter: PropertyFilter = { riskGrade: ['SAFE'] };
    render(<PropertyFilterBar filter={filter} onChange={handleChange} />);

    fireEvent.click(screen.getByRole('button', { name: '안전' }));

    expect(handleChange).toHaveBeenCalledWith({ riskGrade: undefined });
  });

  it('앞쪽 슬롯에 넣은 것은 같은 필터 줄(검색 영역) 안에 그려진다', () => {
    render(
      <PropertyFilterBar
        filter={{}}
        onChange={vi.fn()}
        leading={<button type="button">앞쪽</button>}
      />,
    );

    const bar = screen.getByRole('search', { name: '매물 필터' });
    expect(within(bar).getByRole('button', { name: '앞쪽' })).toBeInTheDocument();
    expect(within(bar).getByLabelText('계약유형')).toBeInTheDocument();
  });
});
