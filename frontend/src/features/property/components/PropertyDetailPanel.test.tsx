// PropertyDetailPanel 렌더링 검증 — 닫기 동작, 미분석 매물 안내, 그 외 오류의 Alert 처리,
// 정상 판정일 때 다섯 블록(판정 근거 · 보증보험 3사 · 권리 침해/경고 · 정합 · 개인 자격) 노출을 확인한다.
//
// 열림 흐름(미리보기 카드의 「상세 보기」 → 패널 마운트)은 여기서 다루지 않는다. HomePage.test.tsx는
// 카카오맵 SDK가 없는 jsdom 상태만 검증하고 있고(window.kakao를 모킹하지 않는다), MapExplorer는 SDK가
// 없으면 마커 오버레이 자체를 그리지 않아 미리보기 카드에 닿을 수 없다. SDK를 모킹하는 새 방식을
// 들이지 않는 한 그 경로는 이 슬라이스에서 검증할 수 없다 — HomePage.test.tsx에도 추가하지 않는다.
// 패널이 열린 뒤의 동작(닫기 · 데이터 렌더)만 여기서 컴포넌트 단위로 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { gradeReasonLabel } from '../../../domain/risk';
import { PROPERTY_DETAIL, propertyHandlers } from '../../../test/msw/handlers/property';
import { RISK_ANALYSIS, riskHandlers } from '../../../test/msw/handlers/risk';
import { server } from '../../../test/msw/server';
import { PropertyDetailPanel } from './PropertyDetailPanel';

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onClose = vi.fn();
  render(
    <QueryClientProvider client={queryClient}>
      <PropertyDetailPanel propertyId={PROPERTY_DETAIL.propertyId} onClose={onClose} />
    </QueryClientProvider>,
  );
  return onClose;
}

describe('PropertyDetailPanel', () => {
  it('닫기 버튼을 누르면 onClose가 호출된다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);
    const onClose = renderPanel();

    // 요청이 끝난 뒤 닫는다 — 언마운트 뒤 응답이 와 상태를 바꾸는 경고를 피한다
    await waitFor(() => expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '상세 닫기' }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('위험도가 RISK_NOT_ANALYZED로 오면 안내를 보여주고 매물 기본 정보는 그대로 보인다', async () => {
    // 안내 본문은 서버가 준 error.message다 — 핸들러와 단언이 같은 상수를 본다.
    // 문구를 테스트에 따로 적으면 서버 문구가 바뀔 때 화면이 낡은 것을 잡지 못한다
    const message = '아직 분석되지 않은 매물입니다.';
    server.use(
      ...propertyHandlers,
      http.get('/api/properties/:propertyId/risk', () =>
        HttpResponse.json({ success: false, error: { code: 'RISK_NOT_ANALYZED', message } }, { status: 404 }),
      ),
    );

    renderPanel();

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(message));
    // 서버 문구에 화면 사정만 덧붙는다
    expect(screen.getByRole('status')).toHaveTextContent('기본 정보만 표시합니다.');
    // 오류 Alert(role=alert)이 아니라 안내(role=status)로 뜬다
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    // 매물 기본 정보는 그대로 보인다
    expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument();
  });

  it('RISK_NOT_ANALYZED가 아닌 오류는 안내가 아니라 오류 Alert으로 뜬다', async () => {
    const message = '외부 연동 장애로 위험도를 불러오지 못했습니다.';
    server.use(
      ...propertyHandlers,
      http.get('/api/properties/:propertyId/risk', () =>
        HttpResponse.json({ success: false, error: { code: 'EXTERNAL_API_UNAVAILABLE', message } }, { status: 503 }),
      ),
    );

    renderPanel();

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(message));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    // 매물 기본 정보는 그대로 보인다
    expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument();
  });

  it('위험도가 정상으로 오면 판정 근거 · 보증보험 3사 · 권리 침해/경고 · 정합 · 개인 자격 블록이 렌더된다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);

    renderPanel();

    // 판정 근거 (RiskVerdict)
    await waitFor(() => expect(screen.getByText(gradeReasonLabel(RISK_ANALYSIS.gradeReason))).toBeInTheDocument());
    // 보증보험 3사 (InsuranceProviders)
    expect(screen.getByText('보증보험 가입 판정')).toBeInTheDocument();
    // 권리 침해 · 경고 (RiskFindings)
    expect(screen.getByText('등기 검출 항목')).toBeInTheDocument();
    // 정합 (ConsistencyCheck)
    expect(screen.getByText('명의 · 문서 정합')).toBeInTheDocument();
    // 개인 자격 (PersonalConditions)
    expect(screen.getByText('직접 확인할 조건')).toBeInTheDocument();
  });
});
