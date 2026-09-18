// 감춰진 지도가 다시 보일 때 relayout을 빠뜨리면 오류도 실패도 없이 타일만 그려지지 않는다 —
// 이슈 114 계획이 뺐던 검증 항목. 카카오맵 SDK는 jsdom에 없어(kakao.d.ts) 이 파일에서만 전역에
// 가짜를 주입한다 — 저장소가 직접 쓴 가짜(frontend/src/test/kakao.ts · resizeObserver.ts)이고,
// 테스트 전략 문서 1.2가 EventSource에 쓴 것과 같은 해법이다.
//
// 다른 지도 테스트(map.test.ts · useMapStage.test.ts)와 MapPage.test.tsx ·
// PropertyDetailPanel.test.tsx는 SDK 없음(window.kakao undefined) 경로를 검증하므로 이 파일의
// 가짜는 여기 하나에만 설치 · 해제한다 — 전역 기본값을 바꾸지 않는다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { installFakeKakaoMaps, getKakaoMapInstances } from '../../../test/kakao';
import { installFakeResizeObserver, getResizeObserverInstances } from '../../../test/resizeObserver';
import { propertyHandlers } from '../../../test/msw/handlers/property';
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
