// BuildingLedgerSection 검증 — 대장 값이 null일 때 「확인 불가」로 보이는지(이슈 308), 대장이 없을 때
// 안내가 뜨는지, 참 · 거짓 표기가 그대로인지 본다. 문구는 domain/property.ts 상수로 확인해 문구가
// 바뀌면 테스트도 함께 따라간다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  LEDGER_MISSING_NOTICE,
  LEDGER_UNVERIFIABLE_LABEL,
  VIOLATION_BUILDING_UNVERIFIABLE_LABEL,
} from '../../../domain/property';
import {
  BUILDING_LEDGER,
  BUILDING_LEDGER_MISSING,
  BUILDING_LEDGER_UNVERIFIED,
  buildingLedgerHandler,
} from '../../../test/msw/handlers/property';
import { server } from '../../../test/msw/server';
import { BuildingLedgerSection } from './BuildingLedgerSection';

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <BuildingLedgerSection propertyId={BUILDING_LEDGER.propertyId} />
    </QueryClientProvider>,
  );
}

/** KvRow는 <dt>라벨</dt><dd>값</dd>이다 — 라벨 옆 값 칸의 글자 */
function valueOf(label: string) {
  return screen.getByText(label).nextElementSibling?.textContent;
}

describe('BuildingLedgerSection', () => {
  it('대장 값이 참 · 거짓이면 기존대로 「해당」 · 「해당 없음」으로 보인다', async () => {
    server.use(buildingLedgerHandler(BUILDING_LEDGER));
    renderSection();

    expect(await screen.findByText(BUILDING_LEDGER.mainPurpose)).toBeInTheDocument();
    expect(valueOf('주거용')).toBe('해당');
    expect(valueOf('위반건축물')).toBe('해당 없음');
    expect(valueOf('연면적')).toBe('480.2㎡');
    expect(screen.queryByText(LEDGER_UNVERIFIABLE_LABEL)).not.toBeInTheDocument();
  });

  it('주거용 · 위반건축물이 null이면 「해당 없음」이 아니라 확인 불가로 보인다', async () => {
    server.use(buildingLedgerHandler(BUILDING_LEDGER_UNVERIFIED));
    renderSection();

    await screen.findByText('위반건축물');
    expect(valueOf('주거용')).toBe(LEDGER_UNVERIFIABLE_LABEL);
    expect(valueOf('위반건축물')).toBe(VIOLATION_BUILDING_UNVERIFIABLE_LABEL);
    expect(screen.queryByText('해당 없음')).not.toBeInTheDocument();
    // 한 필드가 null인 것은 대장 없음이 아니다 — 나머지 행과 수집 시각은 그대로 그려진다
    expect(screen.queryByText(LEDGER_MISSING_NOTICE)).not.toBeInTheDocument();
    expect(valueOf('주용도')).toBe(BUILDING_LEDGER.mainPurpose);
  });

  it('대장을 찾지 못하면(propertyId 밖 전부 null) 오류가 아닌 안내(role=status)로 알리고 행을 그리지 않는다', async () => {
    server.use(buildingLedgerHandler(BUILDING_LEDGER_MISSING));
    renderSection();

    // 불러오는 동안의 Spinner 도 role=status 다 — 안내 문구가 뜰 때까지 기다린 뒤 status 가 그 안내 하나임을 본다
    await screen.findByText(LEDGER_MISSING_NOTICE);
    expect(screen.getByRole('status')).toHaveTextContent(LEDGER_MISSING_NOTICE);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText('위반건축물')).not.toBeInTheDocument();
    expect(screen.queryByText('해당 없음')).not.toBeInTheDocument();
  });
});
