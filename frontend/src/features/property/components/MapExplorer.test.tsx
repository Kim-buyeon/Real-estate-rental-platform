// 감춰진 지도가 다시 보일 때 relayout을 빠뜨리면 오류도 실패도 없이 타일만 그려지지 않는다 —
// 이슈 114 계획이 뺐던 검증 항목. 카카오맵 SDK는 jsdom에 없어(kakao.d.ts) 이 파일에서만 전역에
// 가짜를 주입한다 — 저장소가 직접 쓴 가짜(frontend/src/test/kakao.ts · resizeObserver.ts)이고,
// 테스트 전략 문서 1.2가 EventSource에 쓴 것과 같은 해법이다.
//
// 다른 지도 테스트(map.test.ts · useMapStage.test.ts)와 MapPage.test.tsx ·
// PropertyDetailPanel.test.tsx는 SDK 없음(window.kakao undefined) 경로를 검증하므로 이 파일의
// 가짜는 여기 하나에만 설치 · 해제한다 — 전역 기본값을 바꾸지 않는다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  KakaoAddressSearchResult,
  KakaoCustomOverlayOptions,
  KakaoGeocoder,
  KakaoMaps,
  KakaoStatus,
} from '../map/kakao';
import type { MapStage } from '../hooks/useMapStage';
import { FakeKakaoCustomOverlay, installFakeKakaoMaps, getKakaoMapInstances } from '../../../test/kakao';
import { installFakeResizeObserver, getResizeObserverInstances } from '../../../test/resizeObserver';
import { MAP_CLUSTERS, propertyHandlers } from '../../../test/msw/handlers/property';
import { server } from '../../../test/msw/server';
import { MapExplorer } from './MapExplorer';

let restoreKakao: () => void;
let restoreResizeObserver: () => void;

function renderExplorer(isShown: boolean) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MapExplorer
        filter={{}}
        stage={{ type: 'seoul' }}
        isShown={isShown}
        onSelectDistrict={() => {}}
        onOpenDetail={() => {}}
      />
    </QueryClientProvider>,
  );
  return {
    ...utils,
    setIsShown: (nextIsShown: boolean) =>
      utils.rerender(
        <QueryClientProvider client={queryClient}>
          <MapExplorer
            filter={{}}
            stage={{ type: 'seoul' }}
            isShown={nextIsShown}
            onSelectDistrict={() => {}}
            onOpenDetail={() => {}}
          />
        </QueryClientProvider>,
      ),
  };
}

/**
 * 마운트 시점에 만들어진 지도 인스턴스를 기다려 돌려준다. 컨테이너 크기 확정 뒤의
 * lockSeoulView(requestAnimationFrame)까지 끝난 뒤라야 relayout 호출 수가 안정된다 —
 * lockSeoulView는 relayout 다음에 setMaxLevel을 부르므로 그것을 정착 신호로 쓴다.
 * 그 전에 relayout 호출 수를 기준으로 잡으면 rAF가 비동기로 늦게 불려 다음 단언이 흔들린다.
 */
async function firstSettledMap() {
  await waitFor(() => expect(getKakaoMapInstances()).toHaveLength(1));
  const map = getKakaoMapInstances()[0]!;
  await waitFor(() => expect(map.setMaxLevel).toHaveBeenCalled());
  return map;
}

describe('MapExplorer relayout', () => {
  beforeEach(() => {
    // 서울 전체 단계라 자치구 집계 조회가 항상 걸린다 — 없으면 onUnhandledRequest: 'error'로 실패한다
    server.use(...propertyHandlers);
    restoreKakao = installFakeKakaoMaps();
    restoreResizeObserver = installFakeResizeObserver();
  });

  afterEach(() => {
    restoreKakao();
    restoreResizeObserver();
  });

  it('언마운트하면 크기 관찰을 끊는다', async () => {
    const { unmount } = renderExplorer(true);
    await firstSettledMap();
    await waitFor(() => expect(getResizeObserverInstances()).toHaveLength(1));
    // 바로 위 대기가 인스턴스가 정확히 하나임을 확인했다
    const observer = getResizeObserverInstances()[0]!;
    expect(observer.isDisconnected).toBe(false);

    unmount();

    expect(observer.isDisconnected).toBe(true);
  });

  it('isShown이 거짓에서 참으로 바뀌면 relayout이 불린다', async () => {
    const { setIsShown } = renderExplorer(false);
    // 마운트 자체도(isShown 무관) 컨테이너 크기 확정 뒤 lockSeoulView가 relayout을 부른다 —
    // firstSettledMap이 그 정착을 기다린 뒤이므로 이 시점의 호출 수를 기준으로 삼는다
    const map = await firstSettledMap();
    const callsBeforeShown = map.relayout.mock.calls.length;

    setIsShown(true);

    await waitFor(() => expect(map.relayout.mock.calls.length).toBe(callsBeforeShown + 1));
  });

  it('isShown이 참에서 거짓으로 바뀔 때는 relayout을 부르지 않는다', async () => {
    const { setIsShown } = renderExplorer(true);
    const map = await firstSettledMap();
    const callsWhileShown = map.relayout.mock.calls.length;

    setIsShown(false);

    // 감추는 방향은 relayout이 필요 없다 — 다시 보일 때만 필요하다. waitFor로 기다릴 신호가 없어
    // 다음 틱까지 미뤄 호출이 없었는지 확인한다
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(map.relayout.mock.calls.length).toBe(callsWhileShown);
  });

  it('컨테이너 크기가 0이면 크기 변화 콜백이 와도 relayout을 부르지 않고, 크기가 잡히면 부른다', async () => {
    const { container } = renderExplorer(true);
    const map = await firstSettledMap();
    map.relayout.mockClear();

    await waitFor(() => expect(getResizeObserverInstances()).toHaveLength(1));
    const observer = getResizeObserverInstances()[0]!;
    const mapContainer = container.querySelector('[role="application"]');
    expect(mapContainer).not.toBeNull();

    // jsdom 기본값은 clientWidth · clientHeight가 0이다 — 감춰진 동안 크기가 0이 되는 상황과 같다.
    // 이 가드(container.clientWidth === 0 || container.clientHeight === 0일 때 되돌아간다)가
    // 없으면 크기 0에서도 relayout이 불려 돌아왔을 때 중심 · 확대 수준이 어긋난다
    observer.trigger();
    expect(map.relayout).not.toHaveBeenCalled();

    // 가드가 0일 때만 막는다는 것을 함께 확인한다 — 크기가 잡히면 같은 콜백이 relayout을 부른다
    Object.defineProperty(mapContainer as HTMLElement, 'clientWidth', { value: 400, configurable: true });
    Object.defineProperty(mapContainer as HTMLElement, 'clientHeight', { value: 300, configurable: true });
    observer.trigger();
    expect(map.relayout).toHaveBeenCalledTimes(1);
  });
});

/*
 * SDK가 첫 렌더 뒤에 준비되는 경로 — 실제 브라우저의 동적 로드(kakao-map 2장)와 같은 순서다.
 * 위 describe는 렌더 전에 가짜를 설치해 첫 렌더부터 준비라 이 경로를 타지 않는다.
 *
 * 키를 스텁해 로더가 스크립트를 넣게 하고, 그 스크립트의 load 이벤트와 kakao.maps.load 콜백을
 * 흉내 내 렌더 뒤에 가짜 SDK를 준비시킨다. Geocoder는 좌표를 돌려주는 것으로 바꾸고,
 * 만들어진 오버레이를 모은다.
 */
const geocodeCalls = vi.fn<(address: string) => void>();

class ResolvingGeocoder implements KakaoGeocoder {
  addressSearch(address: string, callback: (result: KakaoAddressSearchResult[], status: KakaoStatus) => void) {
    geocodeCalls(address);
    callback([{ x: '126.8666', y: '37.5170' }], 'OK');
  }
}

let createdOverlays: FakeKakaoCustomOverlay[] = [];

class RecordingOverlay extends FakeKakaoCustomOverlay {
  constructor(options: KakaoCustomOverlayOptions) {
    super(options);
    createdOverlays.push(this);
  }
}

function insertedSdkScript(): HTMLScriptElement | undefined {
  return [...document.head.querySelectorAll<HTMLScriptElement>('script')].find((script) =>
    script.src.includes('dapi.kakao.com/v2/maps/sdk.js'),
  );
}

/** 로더가 넣은 스크립트가 실행되고, SDK 본체가 kakao.maps.load 콜백으로 준비되는 것을 흉내 낸다 */
async function finishSdkLoad() {
  const script = insertedSdkScript();
  expect(script).toBeDefined();
  const partial = {
    maps: {
      load: (callback: () => void) => {
        installFakeKakaoMaps();
        const maps: KakaoMaps = window.kakao.maps;
        window.kakao = {
          maps: {
            ...maps,
            CustomOverlay: RecordingOverlay,
            services: { ...maps.services, Geocoder: ResolvingGeocoder },
          },
        };
        callback();
      },
    },
  };
  window.kakao = partial as unknown as Window['kakao'];
  await act(async () => {
    script!.dispatchEvent(new Event('load'));
  });
}

function renderAt(stage: MapStage) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MapExplorer filter={{}} stage={stage} onSelectDistrict={() => {}} onOpenDetail={() => {}} />
    </QueryClientProvider>,
  );
}

describe('MapExplorer SDK가 렌더 뒤에 준비될 때', () => {
  beforeEach(() => {
    server.use(...propertyHandlers);
    vi.stubEnv('VITE_KAKAO_MAP_KEY', 'test-key');
    restoreResizeObserver = installFakeResizeObserver();
    geocodeCalls.mockClear();
    createdOverlays = [];
  });

  afterEach(() => {
    // setup.ts의 테스트 기본값(키 없음)으로 되돌린다
    vi.stubEnv('VITE_KAKAO_MAP_KEY', '');
    insertedSdkScript()?.remove();
    delete (window as unknown as Record<string, unknown>).kakao;
    restoreResizeObserver();
  });

  it('자치구 단계로 들어온 채 준비되면 그 자치구를 한 번 조회해 그 중심으로 옮긴다', async () => {
    // 자치구 좌표는 map 모듈이 캐시하므로 이 파일의 다른 테스트가 쓰지 않는 구를 쓴다
    renderAt({ type: 'district', district: '양천구' });

    // 로드 중에는 지도를 만들지 않는다
    expect(getKakaoMapInstances()).toHaveLength(0);

    await finishSdkLoad();

    await waitFor(() => expect(getKakaoMapInstances()).toHaveLength(1));
    const map = getKakaoMapInstances()[0]!;
    await waitFor(() => expect(map.setCenter).toHaveBeenCalledTimes(1));
    expect(geocodeCalls).toHaveBeenCalledTimes(1);
    expect(geocodeCalls).toHaveBeenCalledWith('서울특별시 양천구');
    const center = map.setCenter.mock.calls[0]![0] as { getLat(): number; getLng(): number };
    expect(center.getLat()).toBeCloseTo(37.517);
    expect(center.getLng()).toBeCloseTo(126.8666);
  });

  it('서울 전체 단계에서 준비되면 자치구 오버레이를 지도에 그린다', async () => {
    renderAt({ type: 'seoul' });

    await finishSdkLoad();

    await waitFor(() => expect(getKakaoMapInstances()).toHaveLength(1));
    const map = getKakaoMapInstances()[0]!;
    await waitFor(() => expect(createdOverlays.length).toBeGreaterThan(0));
    for (const overlay of createdOverlays) {
      expect(overlay.setMap).toHaveBeenCalledWith(map);
    }
  });

  it('자치구 단계는 지도 묶음을 조회해 서버의 묶음과 개별 마커를 그리고, 묶음을 누르면 그 칸으로 확대한다', async () => {
    const requested: URL[] = [];
    server.use(
      http.get('/api/properties/map-clusters', ({ request }) => {
        requested.push(new URL(request.url));
        return HttpResponse.json({ success: true, data: MAP_CLUSTERS });
      }),
    );
    // 자치구 좌표는 map 모듈이 캐시하므로 이 파일의 다른 테스트가 쓰지 않는 구를 쓴다
    renderAt({ type: 'district', district: '마포구' });

    await finishSdkLoad();

    await waitFor(() => expect(getKakaoMapInstances()).toHaveLength(1));
    const map = getKakaoMapInstances()[0]!;
    const clusterButton = () =>
      createdOverlays
        .map((overlay) => overlay.getContent().querySelector<HTMLButtonElement>('button[aria-label*="묶음"]'))
        .find((button) => button !== null);
    await waitFor(() => expect(clusterButton()).toBeDefined());

    // 요청은 새 경로 하나에 자치구와 표시 영역 넷을 싣는다 (명세 1.12)
    const params = requested.at(-1)!.searchParams;
    expect(params.get('district')).toBe('마포구');
    for (const name of ['minLat', 'maxLat', 'minLng', 'maxLng']) {
      expect(params.has(name)).toBe(true);
    }

    // 묶음 하나 + 개별 마커 하나. 서버가 묶은 것을 다시 묶지 않는다
    const contents = createdOverlays.map((overlay) => overlay.getContent());
    expect(contents.filter((content) => content.querySelector('button[aria-label*="묶음"]'))).toHaveLength(1);
    expect(clusterButton()!.getAttribute('aria-label')).toContain('214');
    expect(contents.filter((content) => content.querySelector('button:not([aria-label])'))).toHaveLength(1);

    map.setBounds.mockClear();
    fireEvent.click(clusterButton()!);

    expect(map.setBounds).toHaveBeenCalledTimes(1);
    const bounds = map.setBounds.mock.calls[0]![0] as {
      getSouthWest(): { getLat(): number; getLng(): number };
      getNorthEast(): { getLat(): number; getLng(): number };
    };
    expect(bounds.getSouthWest().getLat()).toBe(37.545);
    expect(bounds.getSouthWest().getLng()).toBe(126.8567);
    expect(bounds.getNorthEast().getLat()).toBe(37.55);
    expect(bounds.getNorthEast().getLng()).toBe(126.8633);
  });

  it('로드 중에 언마운트하면 로드가 끝나도 지도를 만들지 않고 조회도 하지 않는다', async () => {
    const { unmount } = renderAt({ type: 'district', district: '영등포구' });
    expect(insertedSdkScript()).toBeDefined();

    unmount();
    await finishSdkLoad();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(getKakaoMapInstances()).toHaveLength(0);
    expect(createdOverlays).toHaveLength(0);
    expect(geocodeCalls).not.toHaveBeenCalled();
  });
});
