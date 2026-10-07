// PropertyDetailPanel 렌더링 검증 — 닫기 동작, 미분석 매물 안내, 그 외 오류의 Alert 처리,
// 정상 판정일 때 다섯 블록(판정 근거 · 보증기관 3곳 · 권리 침해/경고 · 정합 · 개인 자격) 노출을 확인한다.
//
// 열림 흐름(미리보기 카드의 「상세 보기」 → 패널 마운트)은 여기서 다루지 않는다. MapPage.test.tsx는
// 카카오맵 SDK가 없는 jsdom 상태만 검증하고 있고(window.kakao를 모킹하지 않는다), MapExplorer는 SDK가
// 없으면 마커 오버레이 자체를 그리지 않아 미리보기 카드에 닿을 수 없다. SDK를 모킹하는 새 방식을
// 들이지 않는 한 그 경로는 이 슬라이스에서 검증할 수 없다 — MapPage.test.tsx에도 추가하지 않는다.
// 패널이 열린 뒤의 동작(닫기 · 데이터 렌더)만 여기서 컴포넌트 단위로 검증한다.
import { QueryClient, QueryClientProvider, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import {
  gradeReasonLabel,
  LANDLORD_NAME_SOURCE_NOTE,
  ownershipRightTypeLabel,
  riskGradeLabel,
} from '../../../domain/risk';
import { propertyQueries, wishlistQueries } from '../../../queries/property';
import { setTokens } from '../../../session/store';
import {
  BUILDING_LEDGER,
  PROPERTY_DETAIL,
  propertyHandlers,
  wishlistHandlers,
} from '../../../test/msw/handlers/property';
import {
  REANALYZE_RESULT,
  REANALYZE_RESULT_FIRST_ANALYSIS,
  REGISTRY,
  RISK_ANALYSIS,
  RISK_ANALYSIS_AFTER_REANALYSIS,
  riskHandlers,
} from '../../../test/msw/handlers/risk';
import { server } from '../../../test/msw/server';
import { PropertyDetailPanel } from './PropertyDetailPanel';
import { ToastProvider } from '../../../components/ui';

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onClose = vi.fn();
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <PropertyDetailPanel propertyId={PROPERTY_DETAIL.propertyId} onClose={onClose} />
      </ToastProvider>
    </QueryClientProvider>,
  );
  return onClose;
}

/**
 * property 루트(자치구 집계 · 지도 마커)를 지도 화면처럼 이미 관측 중인 상태로 둔다 — 무효화 연쇄
 * (나)(다)는 패널 밖의 property 루트 쿼리가 다시 요청되는지(혹은 되지 않는지)를 보는 것이라
 * 그 쿼리를 실제로 마운트해 둬야 한다. 마커 대신 자치구 집계(districtCounts)를 쓴다 — 필터가
 * 없어도(빈 객체) 호출되는 명세 1.5 엔드포인트라 표시 영역 좌표 없이도 관측할 수 있다.
 */
function DistrictCountsProbe() {
  useQuery(propertyQueries.districtCounts({}));
  return null;
}

function renderPanelWithDistrictCountsProbe() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <DistrictCountsProbe />
        <PropertyDetailPanel propertyId={PROPERTY_DETAIL.propertyId} onClose={vi.fn()} />
      </ToastProvider>
    </QueryClientProvider>,
  );
}

/**
 * wishlist 루트를 관심 매물 화면처럼 이미 관측 중인 상태로 두는 관측기 — DistrictCountsProbe와 같은
 * 방식이다. gradeChanged가 참인 재분석 뒤 이 목록도 다시 요청되는지(#91이 자리만 남긴 숙제, 무효화
 * 연쇄 표 「gradeChanged가 참이면 property 전체와 wishlist 전체도」)를 패널 밖에서 관측한다.
 */
function WishlistProbe() {
  useInfiniteQuery(wishlistQueries.list());
  return null;
}

function renderPanelWithWishlistProbe() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <WishlistProbe />
        <PropertyDetailPanel propertyId={PROPERTY_DETAIL.propertyId} onClose={vi.fn()} />
      </ToastProvider>
    </QueryClientProvider>,
  );
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

  it('임대인 행에 값과 함께 「등기 연동 전 예시」 보조 문구가 보인다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);
    renderPanel();

    // 문구는 domain/risk.ts 상수로 확인한다 — 문구가 바뀌면 테스트도 함께 따라간다
    const note = await screen.findByText(LANDLORD_NAME_SOURCE_NOTE);
    // 보조 문구는 임대인 값과 같은 칸(시세 행의 출처 문구와 같은 방식)에 있다
    expect(note.parentElement).toHaveTextContent(PROPERTY_DETAIL.landlordName);
  });

  it('위험 등급이 RISK_NOT_ANALYZED로 오면 안내를 보여주고 매물 기본 정보는 그대로 보인다', async () => {
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

    // 패널에는 Spinner · 토스트 영역도 role=status 다 — 안내는 문구로 특정하고 그 역할이 status 임을 본다
    const notice = await screen.findByText(message, { exact: false });
    expect(notice).toHaveAttribute('role', 'status');
    // 서버 문구에 화면 사정만 덧붙는다
    expect(notice).toHaveTextContent('기본 정보만 표시합니다.');
    // 오류 Alert(role=alert)이 아니라 안내(role=status)로 뜬다
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    // 매물 기본 정보는 그대로 보인다
    expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument();
  });

  it('RISK_NOT_ANALYZED가 아닌 오류는 안내가 아니라 오류 Alert으로 뜬다', async () => {
    const message = '외부 연동 장애로 위험 등급을 불러오지 못했습니다.';
    server.use(
      ...propertyHandlers,
      http.get('/api/properties/:propertyId/risk', () =>
        HttpResponse.json({ success: false, error: { code: 'EXTERNAL_API_UNAVAILABLE', message } }, { status: 503 }),
      ),
    );

    renderPanel();

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(message));
    // RISK_NOT_ANALYZED 안내(status)가 아니다
    expect(screen.queryByText('기본 정보만 표시합니다.', { exact: false })).not.toBeInTheDocument();
    // 매물 기본 정보는 그대로 보인다
    expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument();
  });

  it('위험 등급이 정상으로 오면 판정 근거 · 보증기관 3곳 · 권리 침해/경고 · 정합 · 개인 자격 블록이 렌더된다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);

    renderPanel();

    // 판정 근거 (RiskVerdict)
    await waitFor(() => expect(screen.getByText(gradeReasonLabel(RISK_ANALYSIS.gradeReason))).toBeInTheDocument());
    // 보증기관 3곳 (InsuranceProviders)
    expect(screen.getByText('보증보험 가입 판정')).toBeInTheDocument();
    // 권리 침해 · 경고 (RiskFindings)
    expect(screen.getByText('등기 검출 항목')).toBeInTheDocument();
    // 정합 (ConsistencyCheck)
    expect(screen.getByText('명의 · 문서 정합')).toBeInTheDocument();
    // 개인 자격 (PersonalConditions)
    expect(screen.getByText('직접 확인할 조건')).toBeInTheDocument();
  });

  it('펼치기 전에는 건축물대장 · 등기를 조회하지 않고, 「건축물대장」을 펼친 뒤에야 /ledger를 요청한다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);
    const requestedUrls: string[] = [];
    const onRequestStart = ({ request }: { request: Request }) => requestedUrls.push(request.url);
    server.events.on('request:start', onRequestStart);

    renderPanel();

    // 상세 진입 시 호출은 매물 상세와 위험도 둘이다 (매물 API 명세 1.4) — 대장 · 등기는 아직이다
    await waitFor(() => expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument());
    expect(requestedUrls.some((url) => url.includes('/ledger'))).toBe(false);
    expect(requestedUrls.some((url) => url.includes('/registry'))).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: '건축물대장' }));

    await waitFor(() => expect(requestedUrls.some((url) => url.includes('/ledger'))).toBe(true));
    // 「등기 이력」은 펼치지 않았으므로 여전히 조회되지 않는다
    expect(requestedUrls.some((url) => url.includes('/registry'))).toBe(false);

    server.events.removeListener('request:start', onRequestStart);
  });

  it('펼치면 건축물대장 내용과 등기 이력(갑구 · 을구)이 렌더된다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);
    renderPanel();

    await waitFor(() => expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '건축물대장' }));
    // 위반건축물 여부 등 대장 내용의 대표 문구 하나 — 주용도
    await waitFor(() => expect(screen.getByText(BUILDING_LEDGER.mainPurpose)).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '등기 이력' }));
    // 갑구 · 을구 각 대표 문구 하나. 채권최고액은 RiskVerdict의 선순위채권 합계와 금액이 같아
    // 등기 항목은 다른 필드(권리 유형 · 채권자)로 구분한다
    // 픽스처(REGISTRY)가 각 배열에 항목 하나를 보장한다 — 여기서 단언한다
    const ownership = REGISTRY.ownerships[0]!;
    const mortgage = REGISTRY.mortgages[0]!;
    await waitFor(() => expect(screen.getByText(ownershipRightTypeLabel(ownership.rightType))).toBeInTheDocument());
    expect(screen.getByText(mortgage.creditor)).toBeInTheDocument();
  });

  // 재분석(RISK-08)의 무효화 연쇄(queries/risk.ts useReanalyzeRisk) 검증 셋 — 재분석은 인증
  // 필수라 아래 모두 setTokens로 로그인 상태를 만든다 (frontend/CLAUDE.md 세션 · ReanalysisButton.test.tsx).

  it('재분석 성공 후 패널이 새 등급을 다시 그린다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    // GET /risk는 항상 무효화되므로(gradeChanged와 무관) 두 번째 호출부터 다른 등급을 준다 —
    // 재분석 뒤 RiskVerdict가 실제로 다시 그려지는지가 여기서 보려는 것이다
    let riskRequestCount = 0;
    server.use(
      ...propertyHandlers,
      http.get('/api/properties/:propertyId/risk', () => {
        riskRequestCount += 1;
        return HttpResponse.json({
          success: true,
          data: riskRequestCount === 1 ? RISK_ANALYSIS : RISK_ANALYSIS_AFTER_REANALYSIS,
        });
      }),
      http.get('/api/properties/:propertyId/registry', () => HttpResponse.json({ success: true, data: REGISTRY })),
      http.post('/api/properties/:propertyId/risk/reanalyze', () =>
        HttpResponse.json({ success: true, data: REANALYZE_RESULT }),
      ),
    );

    renderPanel();

    // 첫 조회는 RISK_ANALYSIS(위험) — RiskVerdict가 그 등급 배지를 그린다
    await waitFor(() => expect(screen.getByText(riskGradeLabel(RISK_ANALYSIS.riskGrade))).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '재분석' }));

    // risk.analysis(id)가 무효화되어 재조회되면 새 등급(주의)이 그려진다 — 이전 등급 배지는 사라진다
    await waitFor(() =>
      expect(screen.getByText(riskGradeLabel(RISK_ANALYSIS_AFTER_REANALYSIS.riskGrade))).toBeInTheDocument(),
    );
    expect(screen.queryByText(riskGradeLabel(RISK_ANALYSIS.riskGrade))).not.toBeInTheDocument();
  });

  it('gradeChanged가 참이면 재분석 뒤 패널 밖의 property 루트 쿼리(자치구 집계)가 다시 요청된다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    // riskHandlers의 재분석 핸들러는 REANALYZE_RESULT를 준다 — gradeChanged: true (기존 픽스처)
    server.use(...propertyHandlers, ...riskHandlers);

    const requestedUrls: string[] = [];
    const onRequestStart = ({ request }: { request: Request }) => requestedUrls.push(request.url);
    server.events.on('request:start', onRequestStart);

    renderPanelWithDistrictCountsProbe();

    // 패널의 상세 조회와, 지도 화면을 흉내 낸 자치구 집계 조회가 함께 끝난다
    await waitFor(() => expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument());
    const requestsBefore = requestedUrls.filter((url) => url.includes('/district-counts')).length;
    expect(requestsBefore).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: '재분석' }));

    // 재분석 결과 안내(「바뀌었습니다」)가 뜬 뒤 — property 전체 무효화(queries/risk.ts)가 이미 실행된
    // 시점이다. 패널에는 등기 검출(RiskFindings) · 개인 자격(PersonalConditions)도 role=status
    // Alert이라 getByRole('status') 하나로는 여럿이 잡힌다 — 성공 문구로 특정한다
    await waitFor(() => expect(screen.getByText(/바뀌었습니다/)).toBeInTheDocument());
    await waitFor(() =>
      expect(requestedUrls.filter((url) => url.includes('/district-counts')).length).toBe(requestsBefore + 1),
    );

    server.events.removeListener('request:start', onRequestStart);
  });

  it('gradeChanged가 거짓이면(첫 분석) 재분석 뒤에도 property 루트 쿼리(자치구 집계)가 다시 요청되지 않는다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    // 재분석 응답만 첫 분석(gradeChanged: false)으로 바꾼다 — riskHandlers보다 앞에 둬야 이 경로가
    // 이긴다. MSW는 같은 server.use() 호출 안에서 먼저 나온 핸들러가 매칭을 이긴다
    server.use(
      http.post('/api/properties/:propertyId/risk/reanalyze', () =>
        HttpResponse.json({ success: true, data: REANALYZE_RESULT_FIRST_ANALYSIS }),
      ),
      ...propertyHandlers,
      ...riskHandlers,
    );

    const requestedUrls: string[] = [];
    const onRequestStart = ({ request }: { request: Request }) => requestedUrls.push(request.url);
    server.events.on('request:start', onRequestStart);

    renderPanelWithDistrictCountsProbe();

    await waitFor(() => expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument());
    const requestsBefore = requestedUrls.filter((url) => url.includes('/district-counts')).length;
    expect(requestsBefore).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: '재분석' }));

    // 재분석 결과 안내(「위험 등급은 그대로입니다.」)가 뜬 뒤에도 자치구 집계는 다시 요청되지 않는다 —
    // property 전체 무효화는 gradeChanged가 참일 때만 실행되는 분기다 (queries/risk.ts).
    // getByRole('status')로는 등기 검출 · 개인 자격 Alert과 겹쳐 여럿이 잡히므로 성공 문구로 특정한다
    await waitFor(() => expect(screen.getByText(/그대로입니다/)).toBeInTheDocument());
    expect(requestedUrls.filter((url) => url.includes('/district-counts')).length).toBe(requestsBefore);

    server.events.removeListener('request:start', onRequestStart);
  });

  it('gradeChanged가 참이면 재분석 뒤 패널 밖의 wishlist 목록도 다시 요청된다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    // riskHandlers의 재분석 핸들러는 REANALYZE_RESULT를 준다 — gradeChanged: true (기존 픽스처)
    server.use(...propertyHandlers, ...riskHandlers, ...wishlistHandlers);

    const requestedUrls: string[] = [];
    const onRequestStart = ({ request }: { request: Request }) => requestedUrls.push(request.url);
    server.events.on('request:start', onRequestStart);

    renderPanelWithWishlistProbe();

    // 패널의 상세 조회와, 관심 매물 화면을 흉내 낸 wishlist 목록 조회가 함께 끝난다
    await waitFor(() => expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument());
    const requestsBefore = requestedUrls.filter((url) => url.includes('/me/wishlist')).length;
    expect(requestsBefore).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: '재분석' }));

    await waitFor(() => expect(screen.getByText(/바뀌었습니다/)).toBeInTheDocument());
    await waitFor(() =>
      expect(requestedUrls.filter((url) => url.includes('/me/wishlist')).length).toBe(requestsBefore + 1),
    );

    server.events.removeListener('request:start', onRequestStart);
  });

  it('gradeChanged가 거짓이면(첫 분석) 재분석 뒤에도 wishlist 목록이 다시 요청되지 않는다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    // 재분석 응답만 첫 분석(gradeChanged: false)으로 바꾼다 — riskHandlers보다 앞에 둬야 이 경로가
    // 이긴다. MSW는 같은 server.use() 호출 안에서 먼저 나온 핸들러가 매칭을 이긴다
    server.use(
      http.post('/api/properties/:propertyId/risk/reanalyze', () =>
        HttpResponse.json({ success: true, data: REANALYZE_RESULT_FIRST_ANALYSIS }),
      ),
      ...propertyHandlers,
      ...riskHandlers,
      ...wishlistHandlers,
    );

    const requestedUrls: string[] = [];
    const onRequestStart = ({ request }: { request: Request }) => requestedUrls.push(request.url);
    server.events.on('request:start', onRequestStart);

    renderPanelWithWishlistProbe();

    await waitFor(() => expect(screen.getByText(PROPERTY_DETAIL.address)).toBeInTheDocument());
    const requestsBefore = requestedUrls.filter((url) => url.includes('/me/wishlist')).length;
    expect(requestsBefore).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: '재분석' }));

    // 재분석 결과 안내(「위험 등급은 그대로입니다.」)가 뜬 뒤에도 wishlist는 다시 요청되지 않는다 —
    // wishlist 전체 무효화는 gradeChanged가 참일 때만 실행되는 분기다 (queries/risk.ts).
    await waitFor(() => expect(screen.getByText(/그대로입니다/)).toBeInTheDocument());
    expect(requestedUrls.filter((url) => url.includes('/me/wishlist')).length).toBe(requestsBefore);

    server.events.removeListener('request:start', onRequestStart);
  });

  // 하단 고정 바({components.action-bar}) — 관심은 아이콘 버튼, 재분석은 주 버튼이고 둘 다 바 안에 있다.
  // 안내 문구(비로그인 사유 · 재분석 결과)는 바 높이 92 를 지키려고 바 밖 본문 끝에 낸다
  it('하단 바 안에 관심 아이콘 버튼과 재분석 버튼이 있고, 비로그인 사유는 바 밖에 한 번 나온다', async () => {
    server.use(...propertyHandlers, ...riskHandlers);
    renderPanel();

    const bar = await screen.findByRole('group', { name: '매물 동작' });
    // 토스트가 바 위로 올라서도록 표시를 단다(Toast.module.css)
    expect(bar).toHaveAttribute('data-toast-avoid', 'action-bar');

    const wishlist = within(bar).getByRole('button', { name: '관심 등록' });
    expect(wishlist).toHaveAttribute('aria-pressed', String(PROPERTY_DETAIL.wishlisted));
    expect(wishlist).toBeDisabled();
    expect(within(bar).getByRole('button', { name: '재분석' })).toBeDisabled();
    expect(within(bar).getAllByRole('button')).toHaveLength(2);

    const note = screen.getByText('로그인하면 재분석을 요청하고 관심 매물로 등록할 수 있습니다.');
    expect(bar).not.toContainElement(note);
  });

  it('재분석 결과 안내는 바 밖에 나오고 로그인 상태에서는 사유 문구가 없다', async () => {
    setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    server.use(...propertyHandlers, ...riskHandlers);
    renderPanel();

    const bar = await screen.findByRole('group', { name: '매물 동작' });
    expect(screen.queryByText(/로그인하면/)).not.toBeInTheDocument();

    fireEvent.click(within(bar).getByRole('button', { name: '재분석' }));

    const notice = await screen.findByText(/위험 등급이 .*바뀌었습니다/);
    expect(bar).not.toContainElement(notice);
  });
});
