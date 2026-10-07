// Skeleton 이 지키는 것은 접근성 약속 둘이다 — role=status 영역 하나와 화면 낭독기가 읽는 「불러오는 중」.
// 모양(색 · 크기)은 대상이 아니다.
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Skeleton } from '.';

describe('Skeleton', () => {
  it('막대가 여럿이어도 role=status 는 하나이고 「불러오는 중」은 한 번만 있다', () => {
    render(<Skeleton count={4} />);

    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getAllByText('불러오는 중')).toHaveLength(1);
  });

  it('count 만큼 장식 막대를 그리고 막대는 화면 낭독기에서 숨긴다', () => {
    const { container } = render(<Skeleton count={3} />);

    expect(container.querySelectorAll('[aria-hidden="true"]')).toHaveLength(3);
  });

  it('label 을 주면 그 문구를 읽는다', () => {
    render(<Skeleton count={2} label="알림 불러오는 중" />);

    expect(screen.getByRole('status')).toHaveTextContent('알림 불러오는 중');
  });
});
