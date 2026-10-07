import { Button, useToast } from '../../../components/ui';
import { RISK_REANALYZE_TOO_SOON } from '../../../domain/risk';
import type { useReanalyzeRisk } from '../../../queries/risk';
import { useSession } from '../../../session/useSession';

/** 재분석 뮤테이션 하나. 버튼(바 안)과 결과 안내(바 밖)가 같은 요청 상태를 읽도록 부모가 한 번 만들어 내려준다 */
export type ReanalysisMutation = ReturnType<typeof useReanalyzeRisk>;

interface ReanalysisButtonProps {
  reanalysis: ReanalysisMutation;
  className?: string;
}

/**
 * 위험 등급 재분석 요청 (RISK-08). 인증 「필수」다 — 위험도 API 명세 1장.
 *
 * 상세 패널 하단 {components.action-bar}의 {components.button-primary}다 — 패널에서 서버에 요청을
 * 보내는 동작이 재분석과 관심 둘이고, 관심이 {components.button-icon}이므로 남은 주 동작이 이것이다.
 *
 * 버튼만 갖는다. 간격 제한 · 결과 안내는 바 높이(92)를 지키기 위해 ReanalysisNotice가 바 밖에 낸다 —
 * 같은 요청 상태를 읽도록 뮤테이션은 부모가 만들어 둘에 내려준다.
 *
 * 비로그인이면 버튼을 숨기지 않고 비활성으로 둔다. 숨기면 왜 없는지 알 수 없고, 로그인 화면으로
 * 보내면 지도 위치 · 확대 수준 · 필터와 열린 패널이 날아간다 (이슈 #91 계획 「정한 것」).
 */
export function ReanalysisButton({ reanalysis, className }: ReanalysisButtonProps) {
  const { isAuthenticated } = useSession();
  const toast = useToast();

  return (
    <Button
      type="button"
      className={className}
      variant="primary"
      onClick={() =>
        reanalysis.mutate(undefined, {
          // 실패는 뮤테이션 실패라 Toast 다(frontend/CLAUDE.md 「뮤테이션 실패는 Toast」). 문구는 서버 error.message
          // 그대로다. 간격 제한은 실패가 아니라 「잠시 뒤 가능」이라 ReanalysisNotice 의 안내(Alert info)로 남긴다 — 명세 1.2
          onError: (failure) => {
            if (failure.code !== RISK_REANALYZE_TOO_SOON) toast.show(failure.message, { variant: 'error' });
          },
        })
      }
      isLoading={reanalysis.isPending}
      disabled={!isAuthenticated}
    >
      재분석
    </Button>
  );
}
