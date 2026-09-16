import { describe, expect, it } from 'vitest';
import { formatDepositShort, formatPercent, formatWon } from './format';

describe('formatDepositShort', () => {
  it('1만 미만은 원 단위로 적는다', () => {
    expect(formatDepositShort(0)).toBe('0원');
    expect(formatDepositShort(9_999)).toBe('9,999원');
  });

  it('1억 미만 1만 이상은 만 단위 정수로 적는다', () => {
    expect(formatDepositShort(10_000)).toBe('1만');
    expect(formatDepositShort(95_000_000)).toBe('9,500만');
  });

  it('만 단위는 반올림한다 — 버리지 않는다', () => {
    expect(formatDepositShort(15_000)).toBe('2만');
    expect(formatDepositShort(14_999)).toBe('1만');
  });

  it('반올림한 만 단위가 1억에 닿으면 억으로 올려 적는다', () => {
    // 10,000만으로 적지 않는다
    expect(formatDepositShort(99_999_999)).toBe('1억');
  });

  it('1억 이상은 억 단위 소수 1자리로 적고 소수부가 0이면 생략한다', () => {
    expect(formatDepositShort(100_000_000)).toBe('1억');
    expect(formatDepositShort(150_000_000)).toBe('1.5억');
    expect(formatDepositShort(200_000_000)).toBe('2억');
    expect(formatDepositShort(230_000_000)).toBe('2.3억');
  });

  it('억 단위 소수도 반올림한다', () => {
    // 12.34억
    expect(formatDepositShort(1_234_000_000)).toBe('12.3억');
    // 12.35억 → 12.4억
    expect(formatDepositShort(1_235_000_000)).toBe('12.4억');
  });
});

describe('formatWon', () => {
  it('원 단위 정수에 천단위 구분을 넣는다', () => {
    expect(formatWon(150_000_000)).toBe('150,000,000원');
    expect(formatWon(0)).toBe('0원');
  });
});

describe('formatPercent', () => {
  it('비율 수치를 백분율로 적고 소수부가 0이면 생략한다', () => {
    expect(formatPercent(68.0)).toBe('68%');
    expect(formatPercent(116.74)).toBe('116.7%');
  });
});
