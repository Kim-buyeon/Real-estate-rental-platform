import { describe, expect, it } from 'vitest';

import { BBOX_PRECISION, BBOX_TILE_UNITS_BY_LEVEL, DISTRICT_LEVEL } from './constants';
import { bboxTileUnits, roundOutward } from './map';
import type { RawBoundingBox } from './map';

/**
 * 표시 영역을 지도 레벨의 타일 배수로 밖으로 넓히는 것만 검증한다 — SDK 없이 도는 순수 함수다.
 * 규칙은 kakao-map 4장: min은 내림, max는 올림. 타일 크기는 constants 「타일 크기」 표.
 */
const UNIT = 10 ** BBOX_PRECISION;

const decimals = (value: number) => {
  const [, fraction = ''] = String(value).split('.');
  return fraction.length;
};

/** 값이 타일 경계(타일 크기의 정수배)에 있는가 */
const onTileEdge = (value: number, tile: number) => {
  const units = Math.round(value * UNIT);
  return Math.abs(value * UNIT - units) < 1e-6 && units % tile === 0;
};

describe('bboxTileUnits', () => {
  it('레벨이 하나 오를 때마다 두 배다 — 해상도가 레벨마다 두 배인 것과 같은 비율', () => {
    for (let level = 2; level <= BBOX_TILE_UNITS_BY_LEVEL.length; level += 1) {
      expect(bboxTileUnits(level)).toBe(bboxTileUnits(level - 1) * 2);
    }
  });

  it('레벨 3은 0.0128도, 자치구 진입 레벨 7은 0.2048도다', () => {
    expect(bboxTileUnits(3) / UNIT).toBe(0.0128);
    expect(bboxTileUnits(7) / UNIT).toBe(0.2048);
    expect(bboxTileUnits(DISTRICT_LEVEL)).toBe(2_048);
  });

  it('표 밖 레벨은 가장 가까운 끝 값을 쓴다', () => {
    expect(bboxTileUnits(0)).toBe(BBOX_TILE_UNITS_BY_LEVEL[0]);
    expect(bboxTileUnits(-3)).toBe(BBOX_TILE_UNITS_BY_LEVEL[0]);
    expect(bboxTileUnits(99)).toBe(BBOX_TILE_UNITS_BY_LEVEL.at(-1));
  });
});

describe('roundOutward', () => {
  it('min은 타일 경계로 내림하고 max는 올림해 영역을 밖으로 넓힌다 — 레벨 3(0.0128도)', () => {
    const raw: RawBoundingBox = {
      minLat: 37.5501234,
      maxLat: 37.5609876,
      minLng: 126.8497561,
      maxLng: 126.8600031,
    };

    // 37.5501234 → floor(375501.234 / 128) = 2933 → 2933 × 128 = 375424
    expect(roundOutward(raw, 3)).toEqual({
      minLat: 37.5424,
      maxLat: 37.568,
      minLng: 126.848,
      maxLng: 126.8608,
    });
  });

  it('같은 타일 안의 미세한 이동 · 다른 화면 크기는 같은 영역이 된다', () => {
    const level = 5; // 0.0512도
    const desktop = roundOutward({ minLat: 37.5321, maxLat: 37.5703, minLng: 126.8231, maxLng: 126.8907 }, level);
    const nudged = roundOutward({ minLat: 37.5329, maxLat: 37.5711, minLng: 126.8244, maxLng: 126.8916 }, level);
    const mobile = roundOutward({ minLat: 37.5402, maxLat: 37.5622, minLng: 126.8401, maxLng: 126.875 }, level);

    expect(nudged).toEqual(desktop);
    expect(mobile).toEqual(desktop);
  });

  it('레벨이 다르면 타일이 달라 영역도 다르다', () => {
    const raw: RawBoundingBox = { minLat: 37.5321, maxLat: 37.5703, minLng: 126.8231, maxLng: 126.8907 };

    expect(roundOutward(raw, 5)).not.toEqual(roundOutward(raw, 6));
  });

  it('모든 레벨에서 맞춘 영역이 원래 영역을 포함하고, 네 값이 그 레벨의 타일 경계에 있다', () => {
    const raw: RawBoundingBox = {
      minLat: 37.4100001,
      maxLat: 37.7199999,
      minLng: 126.7300001,
      maxLng: 127.2699999,
    };

    for (let level = 1; level <= BBOX_TILE_UNITS_BY_LEVEL.length; level += 1) {
      const tile = bboxTileUnits(level);
      const aligned = roundOutward(raw, level);

      expect(aligned.minLat).toBeLessThanOrEqual(raw.minLat);
      expect(aligned.maxLat).toBeGreaterThanOrEqual(raw.maxLat);
      expect(aligned.minLng).toBeLessThanOrEqual(raw.minLng);
      expect(aligned.maxLng).toBeGreaterThanOrEqual(raw.maxLng);
      for (const value of Object.values(aligned)) {
        expect(onTileEdge(value, tile)).toBe(true);
      }
    }
  });

  it('넓어진 폭은 한 축에 타일 두 칸을 넘지 않는다 — 원래 폭이 타일 하나 이하일 때', () => {
    const level = 7;
    const tile = bboxTileUnits(level) / UNIT;
    // 타일 경계 37.4784 를 가로지르는, 타일보다 좁은 영역
    const aligned = roundOutward({ minLat: 37.4, maxLat: 37.55, minLng: 126.95, maxLng: 127.05 }, level);

    expect(aligned.maxLat - aligned.minLat).toBeCloseTo(tile * 2, 6);
    expect(aligned.maxLng - aligned.minLng).toBeLessThanOrEqual(tile * 2 + 1e-9);
  });

  it(`소수 ${BBOX_PRECISION}자리를 넘지 않는다`, () => {
    for (let level = 1; level <= BBOX_TILE_UNITS_BY_LEVEL.length; level += 1) {
      const aligned = roundOutward(
        { minLat: 37.41234567, maxLat: 37.71987654, minLng: 126.73456789, maxLng: 127.26987654 },
        level,
      );
      for (const value of Object.values(aligned)) {
        expect(decimals(value)).toBeLessThanOrEqual(BBOX_PRECISION);
      }
    }
  });

  it('이미 타일 경계인 값은 그대로 둔다 — 부동소수 오차로 한 칸 밀리지 않는다', () => {
    // 레벨 3 타일 128 단위의 배수: 375424 · 375680 · 1268480 · 1268608
    const raw: RawBoundingBox = {
      minLat: 37.5424,
      maxLat: 37.568,
      minLng: 126.848,
      maxLng: 126.8608,
    };

    expect(roundOutward(raw, 3)).toEqual(raw);
  });

  it('서울 경계를 넘어 넓어져도 위도 · 경도가 서울 부근 양수로 남는다 — 넘친 부분은 서버가 district로 거른다', () => {
    const aligned = roundOutward({ minLat: 37.413579, maxLat: 37.716543, minLng: 126.735791, maxLng: 127.264321 }, 8);

    // 레벨 8 타일 0.4096도 — 서울 경계(37.41 ~ 37.72 · 126.73 ~ 127.27)보다 한 칸씩 밖으로 나간다
    expect(aligned).toEqual({
      minLat: 37.2736,
      maxLat: 38.0928,
      minLng: 126.5664,
      maxLng: 127.3856,
    });
  });
});
