/**
 * 카카오맵 SDK를 지도 화면에서만 내려받는다 — kakao-map 2장.
 *
 * `autoload=false`로 스크립트를 넣고 `kakao.maps.load(콜백)` 안에서 준비를 알린다. 스크립트의
 * onload 직후에는 `kakao.maps.Map`이 아직 없고, 콜백 안에서 `Map` · `services`가 준비된다(6장 확인).
 * 그래서 onload가 아니라 load 콜백에서 resolve한다.
 */
import { SDK_LOAD_TIMEOUT_MS } from './constants';

const SDK_URL = '//dapi.kakao.com/v2/maps/sdk.js';

/**
 * SDK가 준비되었는지. `kakao.maps` 객체만으로는 부족하다 — autoload=false면 스크립트 실행 직후에도
 * `kakao.maps`는 있고 `Map`은 아직 없다. 생성자가 붙었는지로 판정한다.
 */
export function isMapSdkReady(): boolean {
  return typeof window !== 'undefined' && typeof window.kakao?.maps?.Map === 'function';
}

/**
 * 진행 중인 로드. 스크립트를 두 번 넣지 않기 위한 모듈 캐시다. 끝나면(성공이든 실패든) 비운다 —
 * 성공한 뒤에는 isMapSdkReady가 먼저 참이 되어 캐시를 볼 일이 없고, 실패한 뒤에는 다음 호출이 다시 시도해야 한다.
 */
let pending: Promise<void> | null = null;

/** 환경 변수를 읽는 곳은 이 한 줄이다 — 키는 비밀이 아니다(kakao-map 1장) */
function readAppKey(): string {
  const key: unknown = import.meta.env.VITE_KAKAO_MAP_KEY;
  return typeof key === 'string' ? key : '';
}

function insertScript(appKey: string): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const script = document.createElement('script');
    let settled = false;
    const fail = (message: string) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timer);
      // 실패한 태그를 남기지 않는다 — 다음 시도가 새 태그를 넣는다
      script.remove();
      reject(new Error(message));
    };
    // 스크립트가 걸려 있거나 load 콜백이 오지 않으면 끝나지 않는다 — 상한을 넘으면 실패로 끝낸다
    const timer = window.setTimeout(
      () => fail(`카카오맵 SDK가 ${SDK_LOAD_TIMEOUT_MS}ms 안에 준비되지 않았다`),
      SDK_LOAD_TIMEOUT_MS,
    );
    const params = new URLSearchParams({ appkey: appKey, libraries: 'services', autoload: 'false' });
    script.src = `${SDK_URL}?${params.toString()}`;
    script.async = true;
    script.onload = () => {
      const maps = window.kakao?.maps;
      if (!maps || typeof maps.load !== 'function') {
        fail('카카오맵 SDK 스크립트가 kakao.maps.load를 만들지 않았다');
        return;
      }
      maps.load(() => {
        // 상한을 넘겨 이미 실패로 끝났으면 늦은 콜백은 무시한다
        if (settled) return;
        settled = true;
        window.clearTimeout(timer);
        resolve();
      });
    };
    script.onerror = () => fail('카카오맵 SDK를 내려받지 못했다');
    document.head.appendChild(script);
  });
}

/**
 * SDK가 준비되면 resolve한다. 여러 곳이 동시에 불러도 스크립트는 한 번만 넣는다.
 * 이미 준비되어 있으면(테스트 가짜 포함) 스크립트 없이 곧바로 resolve한다.
 * 키가 없거나 스크립트가 실패하거나 상한(SDK_LOAD_TIMEOUT_MS) 안에 load 콜백이 오지 않으면 reject한다 —
 * 캐시는 비워져 다음 호출이 다시 시도한다.
 */
export function loadKakaoMaps(): Promise<void> {
  if (isMapSdkReady()) {
    return Promise.resolve();
  }
  if (pending) {
    return pending;
  }

  const appKey = readAppKey();
  const attempt = appKey
    ? insertScript(appKey)
    : Promise.reject(new Error('카카오맵 JavaScript 키(VITE_KAKAO_MAP_KEY)가 없다'));
  pending = attempt;
  const clear = () => {
    if (pending === attempt) {
      pending = null;
    }
  };
  attempt.then(clear, clear);
  return attempt;
}
