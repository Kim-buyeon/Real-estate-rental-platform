import { describe, expect, it } from 'vitest';

import { BBOX_PRECISION } from './constants';
import { roundOutward } from './map';
import type { RawBoundingBox } from './map';

/**
 * 표시 영역 좌표의 밖으로 반올림만 검증한다 — SDK 없이 도는 순수 함수다.
 * 규칙은 kakao-map 4장: min은 내림, max는 올림.
 */
describe('roundOutward', () => {
  const decimals = (value: number) => {
    const [, fraction = ''] = String(value).split('.');
    return fraction.length;
  };

  it('min은 내림하고 max는 올림해 영역을 밖으로 넓힌다', () => {
    const raw: RawBoundingBox = {
      minLat: 37.5501234,
      maxLat: 37.5809876,
      minLng: 126.8497561,
      maxLng: 126.8800031,
    };

    expect(roundOutward(raw)).toEqual({
      minLat: 37.5501,
      maxLat: 37.581,
      minLng: 126.8497,
      maxLng: 126.8801,
    });
  });

  it('반올림한 영역이 원래 영역을 반드시 포함한다', () => {
    const raw: RawBoundingBox = {
      minLat: 37.4100001,
      maxLat: 37.7199999,
      minLng: 126.7300001,
      maxLng: 127.2699999,
    };

    const rounded = roundOutward(raw);

    expect(rounded.minLat).toBeLessThanOrEqual(raw.minLat);
    expect(rounded.maxLat).toBeGreaterThanOrEqual(raw.maxLat);
    expect(rounded.minLng).toBeLessThanOrEqual(raw.minLng);
    expect(rounded.maxLng).toBeGreaterThanOrEqual(raw.maxLng);
  });

  it('소수 4자리를 넘지 않는다', () => {
    const rounded = roundOutward({
      minLat: 37.41234567,
      maxLat: 37.71987654,
      minLng: 126.73456789,
      maxLng: 127.26987654,
    });

    for (const value of Object.values(rounded)) {
      expect(decimals(value)).toBeLessThanOrEqual(BBOX_PRECISION);
    }
  });

  it('이미 소수 4자리인 값은 그대로 둔다 — 부동소수 오차로 한 칸 밀리지 않는다', () => {
    const raw: RawBoundingBox = {
      minLat: 37.5678,
      maxLat: 37.5679,
      minLng: 126.8497,
      maxLng: 126.8801,
    };

    expect(roundOutward(raw)).toEqual(raw);
  });

  it('서울 좌표 범위에서 위도·경도가 양수로 유지된다', () => {
    const rounded = roundOutward({
      minLat: 37.413579,
      maxLat: 37.716543,
      minLng: 126.735791,
      maxLng: 127.264321,
    });

    expect(rounded).toEqual({
      minLat: 37.4135,
      maxLat: 37.7166,
      minLng: 126.7357,
      maxLng: 127.2644,
    });
  });
});
