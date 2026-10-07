import { useEffect, useRef } from 'react';
import { Alert } from '../../../components/ui';
import { RISK_REANALYZE_TOO_SOON, riskGradeLabel } from '../../../domain/risk';
import { formatDateTime } from '../../../lib/format';
import type { ReanalysisMutation } from './ReanalysisButton';

interface ReanalysisNoticeProps {
  reanalysis: ReanalysisMutation;
}

/**
 * 재분석(RISK-08)의 결과 안내 — 간격 제한(잠시 뒤 가능)과 등급 변경 여부. ReanalysisButton과 같은 뮤테이션을
 * 읽는다. 버튼이 놓인 하단 바는 높이가 고정이라 안내는 여기서 바 밖에 낸다. 낼 것이 없으면 아무것도 그리지 않는다.
 *
 * 바 밖은 패널 본문 끝이라 사용자가 위쪽을 보던 중이면 안내가 화면 밖에 뜬다. 그래서 새 안내가 나오면 그 자리까지
 * 본문을 스크롤한다 — block: 'nearest' 라 이미 보이면 움직이지 않는다.
 *
 * 성공하면 위험도 · 상세 · 등기 쿼리가 무효화되어(queries/risk.ts) 패널이 새 등급을 스스로 다시
 * 그린다. 여기서는 등급이 바뀌었는지만 알린다.
 */
export function ReanalysisNotice({ reanalysis }: ReanalysisNoticeProps) {
  const error = reanalysis.error;
  // 간격 제한은 실패가 아니라 「잠시 뒤 가능」이다 — 명세 1.2. 다음 요청 가능 시각을 함께 낸다
  const isTooSoon = error?.code === RISK_REANALYZE_TOO_SOON;
  const result = reanalysis.data;
  // 지금 보이는 안내의 근거 객체. 요청마다 새 객체라 같은 문구가 다시 나와도 다시 스크롤한다
  const shown = isTooSoon ? error : result;
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = ref.current;
    // jsdom 에는 scrollIntoView 가 없다 — 있을 때만 부른다
    if (shown && element && typeof element.scrollIntoView === 'function') {
      element.scrollIntoView({ block: 'nearest' });
    }
  }, [shown]);

  if (!shown) return null;

  return (
    <div ref={ref}>
      {/* 문구는 서버 error.message 그대로다. 다음 요청 가능 시각만 화면이 덧붙인다. 그 밖의 실패는 Toast 다(ReanalysisButton) */}
      {isTooSoon && (
        <Alert variant="info">
          {error.message}
          {error.retryAfter !== undefined && ` ${formatDateTime(error.retryAfter)}부터 가능합니다.`}
        </Alert>
      )}

      {result && (
        <Alert variant="info">
          {result.gradeChanged
            ? `위험 등급이 ${riskGradeLabel(result.previousGrade)}에서 ${riskGradeLabel(result.riskGrade)}(으)로 바뀌었습니다.`
            : '위험 등급은 그대로입니다.'}{' '}
          {formatDateTime(result.analyzedAt)} 기준
        </Alert>
      )}
    </div>
  );
}
