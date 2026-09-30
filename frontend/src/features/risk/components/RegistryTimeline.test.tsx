// RegistryTimeline 검증 — 등기 응답의 dataSource가 MOCK이면 상단에 예시 데이터 안내(info Alert)가
// 뜨는지 확인한다 (이슈 #305). 문구는 domain/risk.ts 매핑으로 확인해 문구가 바뀌면 테스트도 함께 따라간다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { REGISTRY_DATA_SOURCE_NOTICE } from '../../../domain/risk';
import { REGISTRY, riskHandlers } from '../../../test/msw/handlers/risk';
import { server } from '../../../test/msw/server';
import { RegistryTimeline } from './RegistryTimeline';

function renderTimeline() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <RegistryTimeline propertyId={REGISTRY.propertyId} />
    </QueryClientProvider>,
  );
}

describe('RegistryTimeline', () => {
  it('dataSource가 MOCK이면 상단에 예시 데이터 안내가 오류가 아닌 안내(role=status)로 뜬다', async () => {
    server.use(...riskHandlers);
    renderTimeline();

    // 픽스처(REGISTRY)는 명세 1.3 예시 그대로 dataSource: 'MOCK'이다
    expect(REGISTRY.dataSource).toBe('MOCK');
    const expected = REGISTRY_DATA_SOURCE_NOTICE.MOCK;
    // null이면 빈 문자열 비교가 무엇이든 통과하므로 먼저 막는다
    expect(expected).not.toBeNull();
    const notice = await screen.findByRole('status');
    expect(notice).toHaveTextContent(String(expected));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    // 안내가 등기 내용을 대신하지 않는다 — 갑구 · 을구는 그대로 그려진다
    expect(screen.getByText('갑구 · 소유권')).toBeInTheDocument();
    expect(screen.getByText('을구 · 근저당')).toBeInTheDocument();
  });
});
