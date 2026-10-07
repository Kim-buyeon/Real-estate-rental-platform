// 토스트의 동작만 본다 — 띄움 · 2.5초 뒤 사라짐 · 새 호출이 앞의 것을 교체하고 타이머를 다시 거는지,
// Provider 밖 사용이 막히는지. 모양(색 · 위치)은 대상이 아니다.
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { COMING_SOON_MESSAGE, TOAST_DURATION_MS, ToastProvider, useComingSoon, useToast } from '.';

function Trigger({ message, error }: { message: string; error?: boolean }) {
  const { show } = useToast();
  return (
    <button type="button" onClick={() => show(message, error ? { variant: 'error' } : undefined)}>
      {message} 띄우기
    </button>
  );
}

function ComingSoonTrigger() {
  const comingSoon = useComingSoon();
  return (
    <button type="button" onClick={comingSoon}>
      준비 중 입구
    </button>
  );
}

describe('Toast', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('알림 영역은 처음부터 role=status · aria-live=polite · aria-atomic 으로 붙어 있고 비어 있다', () => {
    render(
      <ToastProvider>
        <div />
      </ToastProvider>,
    );

    const region = screen.getByRole('status');
    expect(region).toHaveAttribute('aria-live', 'polite');
    expect(region).toHaveAttribute('aria-atomic', 'true');
    expect(region).toBeEmptyDOMElement();
  });

  it('show 하면 문구가 알림 영역에 나타난다', () => {
    render(
      <ToastProvider>
        <Trigger message="저장했습니다" />
      </ToastProvider>,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByRole('status')).toHaveTextContent('저장했습니다');
  });

  it('TOAST_DURATION_MS 직전에는 남아 있고 그 시점에 사라진다', () => {
    render(
      <ToastProvider>
        <Trigger message="저장했습니다" />
      </ToastProvider>,
    );
    fireEvent.click(screen.getByRole('button'));

    act(() => {
      vi.advanceTimersByTime(TOAST_DURATION_MS - 1);
    });
    expect(screen.getByRole('status')).toHaveTextContent('저장했습니다');

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
  });

  it('지속 시간은 2.5초다', () => {
    expect(TOAST_DURATION_MS).toBe(2500);
  });

  it('새 호출은 앞의 것을 교체하고 쌓지 않는다', () => {
    render(
      <ToastProvider>
        <Trigger message="첫째" />
        <Trigger message="둘째" />
      </ToastProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: '첫째 띄우기' }));
    fireEvent.click(screen.getByRole('button', { name: '둘째 띄우기' }));

    const region = screen.getByRole('status');
    expect(region).toHaveTextContent('둘째');
    expect(region).not.toHaveTextContent('첫째');
    expect(region.children).toHaveLength(1);
  });

  it('새 호출은 타이머를 다시 건다 — 앞 호출의 만료 시각에 사라지지 않는다', () => {
    render(
      <ToastProvider>
        <Trigger message="첫째" />
        <Trigger message="둘째" />
      </ToastProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: '첫째 띄우기' }));
    act(() => {
      vi.advanceTimersByTime(TOAST_DURATION_MS - 500);
    });
    fireEvent.click(screen.getByRole('button', { name: '둘째 띄우기' }));

    // 첫째의 원래 만료 시각을 지났다
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByRole('status')).toHaveTextContent('둘째');

    act(() => {
      vi.advanceTimersByTime(TOAST_DURATION_MS - 1000);
    });
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
  });

  it('error 변형도 같은 영역에 문구를 띄운다', () => {
    render(
      <ToastProvider>
        <Trigger message="실패했습니다" error />
      </ToastProvider>,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByRole('status')).toHaveTextContent('실패했습니다');
  });

  it('useComingSoon 은 「아직 준비 중인 기능입니다」를 띄운다', () => {
    render(
      <ToastProvider>
        <ComingSoonTrigger />
      </ToastProvider>,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(COMING_SOON_MESSAGE).toBe('아직 준비 중인 기능입니다');
    expect(screen.getByRole('status')).toHaveTextContent('아직 준비 중인 기능입니다');
  });

  it('useToast 는 ToastProvider 밖에서 쓰면 던진다', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    expect(() => renderHook(() => useToast())).toThrow();

    spy.mockRestore();
  });
});
