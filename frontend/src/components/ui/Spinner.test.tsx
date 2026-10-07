// Spinner 가 지키는 것은 접근성 약속 둘이다 — role=status 영역 하나와 화면 낭독기가 읽는 「불러오는 중」.
// 모양(색 · 크기)은 대상이 아니다.
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Spinner } from '.';

describe('Spinner', () => {
  it('role=status 하나이고 숨김 글자 「불러오는 중」이 접근 이름 자리에 있다', () => {
    render(<Spinner />);

    expect(screen.getByRole('status')).toHaveTextContent('불러오는 중');
  });

  it('label 을 주면 그 문구를 읽는다', () => {
    render(<Spinner label="대출 한도 계산 중" />);

    expect(screen.getByRole('status')).toHaveTextContent('대출 한도 계산 중');
    expect(screen.queryByText('불러오는 중')).not.toBeInTheDocument();
  });

  it('고리는 장식이라 화면 낭독기에서 숨긴다', () => {
    const { container } = render(<Spinner />);

    const ring = container.querySelector('[aria-hidden="true"]');
    expect(ring).not.toBeNull();
    expect(ring).toBeEmptyDOMElement();
  });
});
