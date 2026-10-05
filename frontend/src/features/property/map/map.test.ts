import { describe, expect, it } from 'vitest';

import {
  BBOX_LAT_TILE_UNITS_BY_LEVEL,
  BBOX_LNG_TILE_UNITS_BY_LEVEL,
  BBOX_PRECISION,
  DISTRICT_LEVEL,
  GRID_CELLS_PER_LAT_TILE,
  GRID_CELLS_PER_LNG_TILE,
  GRID_MAX_DIVISIONS,
} from './constants';
import { bboxTileUnits, gridSize, roundOutward } from './map';
import type { RawBoundingBox } from './map';

/**
 * 표시 영역을 지도 레벨의 축별 타일 배수로 밖으로 넓히는 것만 검증한다 — SDK 없이 도는 순수 함수다.
 * 규칙은 kakao-map 4장: min은 내림, max는 올림. 타일 크기는 constants 「타일 크기」 표.
 */
const UNIT = 10 ** BBOX_PRECISION;
const LEVELS = BBOX_LAT_TILE_UNITS_BY_LEVEL.length;

const decimals = (value: number) => {
  const [, fraction = ''] = String(value).split('.');
  return fraction.length;
};

/** 값이 타일 경계(타일 크기의 정수배)에 있는가 */
const onTileEdge = (value: number, tile: number) => {
  const units = Math.round(value * UNIT);
  return Math.abs(value * UNIT - units) < 1e-6 && units % tile === 0;
};

/** 맞춘 영역의 한 축이 타일 몇 칸인가 */
const tileCount = (min: number, max: number, tile: number) => Math.round(((max - min) * UNIT) / tile);

/**
 * 실측 화면 폭(도) — 2026-10-05, Chrome 1920×889 · 지도 1474×677, 중심 서울시청.
 * constants 「타일 크기」 표의 마지막 열과 같은 값이다.
 */
const MEASURED_SCREEN: Record<number, { dLat: number; dLng: number }> = {
  1: { dLat: 0.00153, dLng: 0.00417 },
  2: { dLat: 0.00305, dLng: 0.00834 },
  3: { dLat: 0.0061, dLng: 0.01668 },
  4: { dLat: 0.01221, dLng: 0.03336 },
  5: { dLat: 0.02441, dLng: 0.06673 },
  6: { dLat: 0.04882, dLng: 0.13346 },
  7: { dLat: 0.09765, dLng: 0.26692 },
  8: { dLat: 0.19529, dLng: 0.53384 },
  9: { dLat: 0.39057, dLng: 1.06767 },
};

describe('bboxTileUnits', () => {
  it('위도 · 경도 모두 레벨이 하나 오를 때마다 두 배다 — 해상도가 레벨마다 두 배인 것과 같은 비율', () => {
    for (let level = 2; level <= LEVELS; level += 1) {
      expect(bboxTileUnits(level).lat).toBe(bboxTileUnits(level - 1).lat * 2);
      expect(bboxTileUnits(level).lng).toBe(bboxTileUnits(level - 1).lng * 2);
    }
  });

  it('두 표의 레벨 수가 같고, 모든 레벨에서 경도 타일은 위도 타일의 두 배다', () => {
    expect(BBOX_LNG_TILE_UNITS_BY_LEVEL).toHaveLength(LEVELS);
    for (let level = 1; level <= LEVELS; level += 1) {
      expect(bboxTileUnits(level).lng).toBe(bboxTileUnits(level).lat * 2);
    }
  });

  it('자치구 진입 레벨 7은 위도 0.0512도 · 경도 0.1024도다', () => {
    expect(bboxTileUnits(7).lat / UNIT).toBe(0.0512);
    expect(bboxTileUnits(7).lng / UNIT).toBe(0.1024);
    expect(bboxTileUnits(DISTRICT_LEVEL)).toEqual({ lat: 512, lng: 1_024 });
  });

  it('전 레벨에서 단위가 양의 정수다 — 가장 작은 레벨 1 위도도 8 단위(0.0008도)', () => {
    expect(bboxTileUnits(1)).toEqual({ lat: 8, lng: 16 });
    for (let level = 1; level <= LEVELS; level += 1) {
      const { lat, lng } = bboxTileUnits(level);
      expect(Number.isInteger(lat) && lat > 0).toBe(true);
      expect(Number.isInteger(lng) && lng > 0).toBe(true);
    }
  });

  it('표 밖 레벨은 축마다 가장 가까운 끝 값을 쓴다', () => {
    const first = { lat: BBOX_LAT_TILE_UNITS_BY_LEVEL[0], lng: BBOX_LNG_TILE_UNITS_BY_LEVEL[0] };
    const last = { lat: BBOX_LAT_TILE_UNITS_BY_LEVEL.at(-1), lng: BBOX_LNG_TILE_UNITS_BY_LEVEL.at(-1) };

    expect(bboxTileUnits(0)).toEqual(first);
    expect(bboxTileUnits(-3)).toEqual(first);
    expect(bboxTileUnits(99)).toEqual(last);
  });
});

describe('roundOutward', () => {
  it('min은 내림 · max는 올림하되 위도는 위도 타일, 경도는 경도 타일 경계에 맞춘다 — 레벨 3(0.0032 · 0.0064도)', () => {
    const raw: RawBoundingBox = {
      minLat: 37.5501234,
      maxLat: 37.5609876,
      minLng: 126.8497561,
      maxLng: 126.8600031,
    };

    // 위도 37.5501234 → floor(375501.234 / 32) = 11734 → × 32 = 375488
    // 경도 126.8497561 → floor(1268497.561 / 64) = 19820 → × 64 = 1268480
    expect(roundOutward(raw, 3)).toEqual({
      minLat: 37.5488,
      maxLat: 37.5616,
      minLng: 126.848,
      maxLng: 126.8608,
    });
  });

  it('위도 경계에는 있지만 경도 경계에는 없는 값은 축에 따라 다르게 맞는다', () => {
    // 375456 은 32의 배수(위도 경계)지만 64의 배수가 아니다 — 경도로 오면 한 칸 밖으로
    const aligned = roundOutward({ minLat: 37.5456, maxLat: 37.5456, minLng: 37.5456, maxLng: 37.5456 }, 3);

    expect(aligned.minLat).toBe(37.5456);
    expect(aligned.maxLat).toBe(37.5456);
    expect(aligned.minLng).toBe(37.5424);
    expect(aligned.maxLng).toBe(37.5488);
  });

  it('같은 타일 안의 미세한 이동 · 조금 다른 화면 크기는 같은 영역이 된다', () => {
    const level = 5; // 위도 0.0128도 · 경도 0.0256도
    const base = roundOutward({ minLat: 37.5321, maxLat: 37.5703, minLng: 126.8231, maxLng: 126.8907 }, level);
    const nudged = roundOutward({ minLat: 37.5329, maxLat: 37.5711, minLng: 126.8244, maxLng: 126.8916 }, level);
    const resized = roundOutward({ minLat: 37.5302, maxLat: 37.5801, minLng: 126.824, maxLng: 126.8901 }, level);

    expect(nudged).toEqual(base);
    expect(resized).toEqual(base);
  });

  it('레벨이 다르면 타일이 달라 영역도 다르다', () => {
    const raw: RawBoundingBox = { minLat: 37.5321, maxLat: 37.5703, minLng: 126.8231, maxLng: 126.8907 };

    expect(roundOutward(raw, 5)).not.toEqual(roundOutward(raw, 6));
  });

  it('모든 레벨에서 맞춘 영역이 원래 영역을 포함하고, 네 값이 그 축 · 그 레벨의 타일 경계에 있다', () => {
    const raw: RawBoundingBox = {
      minLat: 37.4100001,
      maxLat: 37.7199999,
      minLng: 126.7300001,
      maxLng: 127.2699999,
    };

    for (let level = 1; level <= LEVELS; level += 1) {
      const tile = bboxTileUnits(level);
      const aligned = roundOutward(raw, level);

      expect(aligned.minLat).toBeLessThanOrEqual(raw.minLat);
      expect(aligned.maxLat).toBeGreaterThanOrEqual(raw.maxLat);
      expect(aligned.minLng).toBeLessThanOrEqual(raw.minLng);
      expect(aligned.maxLng).toBeGreaterThanOrEqual(raw.maxLng);
      expect(onTileEdge(aligned.minLat, tile.lat)).toBe(true);
      expect(onTileEdge(aligned.maxLat, tile.lat)).toBe(true);
      expect(onTileEdge(aligned.minLng, tile.lng)).toBe(true);
      expect(onTileEdge(aligned.maxLng, tile.lng)).toBe(true);
    }
  });

  it('넓어진 폭은 한 축에 그 축 타일 두 칸을 넘지 않는다 — 원래 폭이 타일 하나 이하일 때', () => {
    const level = 7;
    // 위도 경계 37.4784 · 경도 경계 126.976 을 가로지르는, 축마다 타일보다 좁은 영역
    const aligned = roundOutward({ minLat: 37.47, maxLat: 37.5, minLng: 126.95, maxLng: 127.05 }, level);

    expect(aligned).toEqual({ minLat: 37.4272, maxLat: 37.5296, minLng: 126.8736, maxLng: 127.0784 });
    expect(tileCount(aligned.minLat, aligned.maxLat, bboxTileUnits(level).lat)).toBe(2);
    expect(tileCount(aligned.minLng, aligned.maxLng, bboxTileUnits(level).lng)).toBe(2);
  });

  it('실측 화면 폭(레벨 1 ~ 9)은 어디에 있든 위도 2 ~ 3 · 경도 3 ~ 4 타일이 된다', () => {
    for (const [levelKey, { dLat, dLng }] of Object.entries(MEASURED_SCREEN)) {
      const level = Number(levelKey);
      const tile = bboxTileUnits(level);
      // 서울시청에서 출발해 화면을 조금씩 옮겨 가며 타일 경계와의 위치를 바꾼다
      for (let step = 0; step < 40; step += 1) {
        const centerLat = 37.5665 + step * dLat * 0.037;
        const centerLng = 126.978 + step * dLng * 0.029;
        const aligned = roundOutward(
          {
            minLat: centerLat - dLat / 2,
            maxLat: centerLat + dLat / 2,
            minLng: centerLng - dLng / 2,
            maxLng: centerLng + dLng / 2,
          },
          level,
        );
        const latTiles = tileCount(aligned.minLat, aligned.maxLat, tile.lat);
        const lngTiles = tileCount(aligned.minLng, aligned.maxLng, tile.lng);

        expect([2, 3]).toContain(latTiles);
        expect([3, 4]).toContain(lngTiles);
      }
    }
  });

  it('실측 레벨 7 화면(0.09765 × 0.26692도)에서 요청은 화면의 위도 1.05 ~ 1.57배 · 경도 1.15 ~ 1.53배다', () => {
    // 레벨 7은 위 MEASURED_SCREEN 상수에 있는 키라 undefined가 될 수 없다
    const { dLat, dLng } = MEASURED_SCREEN[7]!;
    const tile = bboxTileUnits(7);

    expect((2 * tile.lat) / UNIT / dLat).toBeCloseTo(1.05, 2);
    expect((3 * tile.lat) / UNIT / dLat).toBeCloseTo(1.57, 2);
    expect((3 * tile.lng) / UNIT / dLng).toBeCloseTo(1.15, 2);
    expect((4 * tile.lng) / UNIT / dLng).toBeCloseTo(1.53, 2);
  });

  it(`소수 ${BBOX_PRECISION}자리를 넘지 않는다`, () => {
    for (let level = 1; level <= LEVELS; level += 1) {
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
    // 레벨 3 위도 타일 32 단위의 배수: 375488 · 375616, 경도 타일 64 단위의 배수: 1268480 · 1268608
    const raw: RawBoundingBox = {
      minLat: 37.5488,
      maxLat: 37.5616,
      minLng: 126.848,
      maxLng: 126.8608,
    };

    expect(roundOutward(raw, 3)).toEqual(raw);
  });

  it('서울 경계를 넘어 넓어져도 위도 · 경도가 서울 부근 양수로 남는다 — 넘친 부분은 서버가 district로 거른다', () => {
    const aligned = roundOutward({ minLat: 37.413579, maxLat: 37.716543, minLng: 126.735791, maxLng: 127.264321 }, 8);

    // 레벨 8 타일 위도 0.1024도 · 경도 0.2048도 — 서울 경계(37.41 ~ 37.72 · 126.73 ~ 127.27)보다 밖으로 나간다
    expect(aligned).toEqual({
      minLat: 37.376,
      maxLat: 37.7856,
      minLng: 126.5664,
      maxLng: 127.3856,
    });
  });
});

/** 레벨의 타일로 위도 latTiles장 · 경도 lngTiles장인 맞춘 영역 — 서울시청 부근 타일 경계에서 시작한다 */
const alignedArea = (level: number, latTiles: number, lngTiles: number) => {
  const tile = bboxTileUnits(level);
  const minLatUnits = Math.floor(375665 / tile.lat) * tile.lat;
  const minLngUnits = Math.floor(1269780 / tile.lng) * tile.lng;
  return {
    minLat: minLatUnits / UNIT,
    maxLat: (minLatUnits + latTiles * tile.lat) / UNIT,
    minLng: minLngUnits / UNIT,
    maxLng: (minLngUnits + lngTiles * tile.lng) / UNIT,
  };
};

describe('gridSize', () => {
  it('타일 수 × 타일당 칸 수다 — 자치구 레벨 7, 위도 3타일 · 경도 3타일이면 18행 · 15열', () => {
    expect(gridSize(alignedArea(DISTRICT_LEVEL, 3, 3), DISTRICT_LEVEL)).toEqual({ rows: 18, cols: 15 });
    expect(gridSize(alignedArea(DISTRICT_LEVEL, 2, 4), DISTRICT_LEVEL)).toEqual({
      rows: 2 * GRID_CELLS_PER_LAT_TILE,
      cols: 4 * GRID_CELLS_PER_LNG_TILE,
    });
  });

  it('실측 화면 폭(레벨 1 ~ 9)의 맞춘 영역은 어디서든 상한 안에서 타일당 칸 수를 그대로 쓴다', () => {
    for (const [levelKey, { dLat, dLng }] of Object.entries(MEASURED_SCREEN)) {
      const level = Number(levelKey);
      const tile = bboxTileUnits(level);
      for (let step = 0; step < 40; step += 1) {
        const centerLat = 37.5665 + step * dLat * 0.037;
        const centerLng = 126.978 + step * dLng * 0.029;
        const aligned = roundOutward(
          {
            minLat: centerLat - dLat / 2,
            maxLat: centerLat + dLat / 2,
            minLng: centerLng - dLng / 2,
            maxLng: centerLng + dLng / 2,
          },
          level,
        );
        const { rows, cols } = gridSize(aligned, level);

        expect(rows).toBe(tileCount(aligned.minLat, aligned.maxLat, tile.lat) * GRID_CELLS_PER_LAT_TILE);
        expect(cols).toBe(tileCount(aligned.minLng, aligned.maxLng, tile.lng) * GRID_CELLS_PER_LNG_TILE);
      }
    }
  });

  it('상한을 넘으면 타일당 칸 수를 줄여 칸 경계를 타일 경계에 맞춘다 — 위도 5타일은 5 × 4 = 20행, 경도 6타일은 6 × 4 = 24열', () => {
    const { rows, cols } = gridSize(alignedArea(DISTRICT_LEVEL, 5, 6), DISTRICT_LEVEL);

    expect(rows).toBe(20);
    expect(cols).toBe(24);
    expect(rows % 5).toBe(0);
    expect(cols % 6).toBe(0);
  });

  it(`상한 ${GRID_MAX_DIVISIONS}을 넘지 않는다 — 타일이 상한보다 많으면 타일당 한 칸에서 상한으로 자른다`, () => {
    expect(gridSize(alignedArea(DISTRICT_LEVEL, 24, 24), DISTRICT_LEVEL)).toEqual({ rows: 24, cols: 24 });
    expect(gridSize(alignedArea(DISTRICT_LEVEL, 25, 40), DISTRICT_LEVEL)).toEqual({ rows: 24, cols: 24 });
    expect(gridSize(alignedArea(DISTRICT_LEVEL, 13, 9), DISTRICT_LEVEL)).toEqual({ rows: 13, cols: 18 });
  });

  it('영역이 0이면 타일 하나로 보아 타일당 칸 수다 — 0행 · 0열이 되지 않는다', () => {
    const point = { minLat: 37.5664, maxLat: 37.5664, minLng: 126.9776, maxLng: 126.9776 };

    expect(gridSize(point, DISTRICT_LEVEL)).toEqual({ rows: GRID_CELLS_PER_LAT_TILE, cols: GRID_CELLS_PER_LNG_TILE });
  });

  it('같은 맞춘 영역이라도 레벨이 다르면 행 · 열이 다르다 — 쿼리 키에 행 · 열이 들어가야 하는 이유', () => {
    const area = alignedArea(8, 1, 1);

    expect(gridSize(area, 8)).toEqual({ rows: 6, cols: 5 });
    expect(gridSize(area, 7)).toEqual({ rows: 12, cols: 10 });
  });

  it('표 밖 레벨은 가장 가까운 끝 레벨의 타일로 센다', () => {
    const top = BBOX_LAT_TILE_UNITS_BY_LEVEL.length;

    expect(gridSize(alignedArea(1, 2, 3), 0)).toEqual({ rows: 12, cols: 15 });
    expect(gridSize(alignedArea(1, 2, 3), -3)).toEqual(gridSize(alignedArea(1, 2, 3), 1));
    expect(gridSize(alignedArea(top, 1, 2), top + 5)).toEqual({ rows: 6, cols: 10 });
  });
});
