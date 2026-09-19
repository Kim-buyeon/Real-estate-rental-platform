// SDK 로더 — 스크립트를 한 번만 넣는지, 실패하면 다음 시도가 다시 넣을 수 있는지, 이미 준비된 SDK면
// 스크립트 없이 끝나는지를 본다. jsdom은 외부 스크립트를 실행하지 않으므로 load · error 이벤트를
// 직접 보내고, autoload=false SDK가 스크립트 실행 직후에 만드는 상태(kakao.maps.load만 있음)를 흉내 낸다.
//
// 로더의 캐시는 모듈 변수다. 테스트마다 모듈을 새로 읽어 캐시를 비운다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installFakeKakaoMaps } from '../../../test/kakao';
import { SDK_LOAD_TIMEOUT_MS } from './constants';

type Loader = typeof import('./loader');

const TEST_KEY = 'test-key';

async function freshLoader(): Promise<Loader> {
  vi.resetModules();
  return import('./loader');
}

function sdkScripts(): HTMLScriptElement[] {
  return [...document.head.querySelectorAll<HTMLScriptElement>('script')].filter((script) =>
    script.src.includes('dapi.kakao.com/v2/maps/sdk.js'),
  );
}

/**
 * autoload=false SDK 스크립트가 실행된 직후 상태 — kakao.maps에는 load만 있고 생성자가 없다.
 * load 콜백이 불릴 때 비로소 생성자가 붙는다(가짜 SDK 설치로 흉내 낸다).
 */
function simulateScriptExecuted(): { loadCalls: number } {
  const state = { loadCalls: 0 };
  const partial = {
    maps: {
      load: (callback: () => void) => {
        state.loadCalls += 1;
        installFakeKakaoMaps();
        callback();
      },
    },
  };
  window.kakao = partial as unknown as Window['kakao'];
  return state;
}

function removeKakao() {
  delete (window as unknown as Record<string, unknown>).kakao;
}

describe('loadKakaoMaps', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_KAKAO_MAP_KEY', TEST_KEY);
  });

  afterEach(() => {
    // setup.ts가 둔 테스트 기본값(키 없음)으로 되돌린다 — unstubAllEnvs는 로컬 .env 값을 되살린다
    vi.stubEnv('VITE_KAKAO_MAP_KEY', '');
    for (const script of sdkScripts()) script.remove();
    removeKakao();
  });

  it('이미 SDK가 준비되어 있으면 스크립트를 넣지 않고 곧바로 끝난다', async () => {
    const restore = installFakeKakaoMaps();
    const { loadKakaoMaps } = await freshLoader();

    await expect(loadKakaoMaps()).resolves.toBeUndefined();
    expect(sdkScripts()).toHaveLength(0);
    restore();
  });

  it('여러 번 불러도 스크립트는 한 번만 넣고, onload가 아니라 kakao.maps.load 콜백에서 준비된다', async () => {
    const { loadKakaoMaps, isMapSdkReady } = await freshLoader();

    const first = loadKakaoMaps();
    const second = loadKakaoMaps();

    const scripts = sdkScripts();
    expect(scripts).toHaveLength(1);
    const url = new URL(scripts[0]!.src);
    expect(url.searchParams.get('appkey')).toBe(TEST_KEY);
    expect(url.searchParams.get('libraries')).toBe('services');
    expect(url.searchParams.get('autoload')).toBe('false');

    // 스크립트 실행 직후에는 kakao.maps가 있어도 생성자가 없다 — 준비가 아니다
    const state = simulateScriptExecuted();
    expect(isMapSdkReady()).toBe(false);

    scripts[0]!.dispatchEvent(new Event('load'));

    await expect(first).resolves.toBeUndefined();
    await expect(second).resolves.toBeUndefined();
    expect(state.loadCalls).toBe(1);
    expect(isMapSdkReady()).toBe(true);

    // 준비된 뒤의 호출도 스크립트를 더 넣지 않는다
    await loadKakaoMaps();
    expect(sdkScripts()).toHaveLength(1);
  });

  it('스크립트가 실패하면 reject하고 태그를 치우며, 다음 호출이 새 스크립트로 다시 시도한다', async () => {
    const { loadKakaoMaps } = await freshLoader();

    const failed = loadKakaoMaps();
    const [firstScript] = sdkScripts();
    firstScript!.dispatchEvent(new Event('error'));

    await expect(failed).rejects.toThrow();
    expect(sdkScripts()).toHaveLength(0);

    const retry = loadKakaoMaps();
    const scripts = sdkScripts();
    expect(scripts).toHaveLength(1);
    expect(scripts[0]).not.toBe(firstScript);

    simulateScriptExecuted();
    scripts[0]!.dispatchEvent(new Event('load'));
    await expect(retry).resolves.toBeUndefined();
  });

  it('상한 안에 load 콜백이 오지 않으면 reject하고 태그를 치우며, 다음 호출이 다시 시도한다', async () => {
    vi.useFakeTimers();
    try {
      const { loadKakaoMaps } = await freshLoader();

      const hanging = loadKakaoMaps();
      const [script] = sdkScripts();
      // 스크립트는 실행됐으나 load 콜백을 부르지 않는 SDK
      window.kakao = { maps: { load: () => {} } } as unknown as Window['kakao'];
      script!.dispatchEvent(new Event('load'));

      vi.advanceTimersByTime(SDK_LOAD_TIMEOUT_MS - 1);
      expect(sdkScripts()).toHaveLength(1);

      vi.advanceTimersByTime(1);
      await expect(hanging).rejects.toThrow();
      expect(sdkScripts()).toHaveLength(0);

      // 캐시가 비워져 새 태그를 넣는다
      void loadKakaoMaps().catch(() => {});
      expect(sdkScripts()).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('JavaScript 키가 없으면 스크립트를 넣지 않고 reject한다', async () => {
    vi.stubEnv('VITE_KAKAO_MAP_KEY', '');
    const { loadKakaoMaps } = await freshLoader();

    await expect(loadKakaoMaps()).rejects.toThrow();
    expect(sdkScripts()).toHaveLength(0);
  });
});
