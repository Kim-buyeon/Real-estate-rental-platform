import { requireMaps } from './map';
import type { KakaoCustomOverlay, KakaoMap } from './kakao';

/**
 * 오버레이 하나가 필요로 하는 것.
 *
 * element는 호출자(컴포넌트)가 만든다 — 이 파일은 붙이고 떼는 일만 한다.
 * 그래야 오버레이를 지울 때 element에 걸린 DOM 리스너가 element와 함께 버려진다.
 */
export interface OverlayItem {
  key: string;
  lat: number;
  lng: number;
  element: HTMLElement;
  /** 겹칠 때 앞으로 올릴 것. 생략하면 0 */
  zIndex?: number;
}

export interface OverlayLayer {
  /** key로 diff한다 — 새 것만 만들고 사라진 것만 지운다. 전부 지우고 다시 그리지 않는다 */
  sync(items: OverlayItem[]): void;
  /** 단계가 바뀌면 이전 단계의 오버레이를 전부 지운다 (kakao-map 5장) */
  clear(): void;
}

interface Placed {
  overlay: KakaoCustomOverlay;
  lat: number;
  lng: number;
  zIndex: number;
}

export function createOverlayLayer(map: KakaoMap): OverlayLayer {
  const placed = new Map<string, Placed>();

  function place(item: OverlayItem): Placed {
    const maps = requireMaps();
    const zIndex = item.zIndex ?? 0;
    const overlay = new maps.CustomOverlay({
      position: new maps.LatLng(item.lat, item.lng),
      content: item.element,
      clickable: true,
      zIndex,
    });
    overlay.setMap(map);
    return { overlay, lat: item.lat, lng: item.lng, zIndex };
  }

  function move(current: Placed, item: OverlayItem): Placed {
    const zIndex = item.zIndex ?? 0;
    if (current.zIndex !== zIndex) {
      current.overlay.setZIndex(zIndex);
    }
    if (current.lat === item.lat && current.lng === item.lng) {
      return { ...current, zIndex };
    }
    const maps = requireMaps();
    current.overlay.setPosition(new maps.LatLng(item.lat, item.lng));
    return { overlay: current.overlay, lat: item.lat, lng: item.lng, zIndex };
  }

  return {
    sync(items) {
      const nextKeys = new Set<string>();
      for (const item of items) {
        nextKeys.add(item.key);
        const current = placed.get(item.key);
        if (!current) {
          placed.set(item.key, place(item));
          continue;
        }
        if (current.overlay.getContent() !== item.element) {
          // 내용 엘리먼트가 바뀐 것만 교체한다. 이전 element와 그 DOM 리스너는 함께 버려진다
          current.overlay.setMap(null);
          placed.set(item.key, place(item));
          continue;
        }
        placed.set(item.key, move(current, item));
      }
      for (const [key, current] of placed) {
        if (!nextKeys.has(key)) {
          current.overlay.setMap(null);
          placed.delete(key);
        }
      }
    },
    clear() {
      for (const current of placed.values()) {
        current.overlay.setMap(null);
      }
      placed.clear();
    },
  };
}
