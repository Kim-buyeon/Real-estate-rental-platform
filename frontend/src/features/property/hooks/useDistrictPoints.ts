import { useEffect, useState } from 'react';
import { searchDistrictPoint, type MapPoint } from '../map';

/**
 * 자치구 이름 → 중심 좌표. 좌표 상수를 저장소에 두지 않고 Geocoder가 돌려주는 값을 쓴다 (계획 ①).
 * 같은 구는 map 모듈의 캐시가 막으므로 화면을 오가도 다시 조회하지 않는다.
 * SDK 준비는 호출자가 판단해 빈 배열을 넘긴다 — 조회 자체도 SDK 로드를 기다린다(searchDistrictPoint).
 */
export function useDistrictPoints(districts: string[]): Record<string, MapPoint> {
  const [points, setPoints] = useState<Record<string, MapPoint>>({});
  const key = districts.join(',');

  useEffect(() => {
    if (!key) return;
    let cancelled = false;

    const names = key.split(',');
    Promise.all(
      names.map((name) =>
        searchDistrictPoint(name)
          .then((point) => [name, point] as const)
          .catch(() => null),
      ),
    ).then((resolved) => {
      if (cancelled) return;
      const found = resolved.filter((entry): entry is readonly [string, MapPoint] => entry !== null);
      if (found.length === 0) return;
      setPoints((previous) => ({ ...previous, ...Object.fromEntries(found) }));
    });

    return () => {
      cancelled = true;
    };
  }, [key]);

  return points;
}
