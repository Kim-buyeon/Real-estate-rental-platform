import { Alert, Button } from '../../../components/ui';
import { RISK_REANALYZE_TOO_SOON, riskGradeLabel } from '../../../domain/risk';
import { formatDateTime } from '../../../lib/format';
import { useReanalyzeRisk } from '../../../queries/risk';
import { useSession } from '../../../session/useSession';
import styles from './ReanalysisButton.module.css';

interface ReanalysisButtonProps {
  propertyId: number;
}

/**
 * 위험도 재분석 요청 (RISK-08). 인증 「필수」다 — 위험도 API 명세 1장.
 *
 * 상세 패널 하단의 {components.action-bar} 안에 들어간다 — 카드가 아니라 버튼 하나와 그 결과
 * 문구다. 설명 문구를 두지 않는 것은 바 높이(92)가 그것을 담을 자리가 아니기 때문이다.
 *
 * 비로그인이면 버튼을 숨기지 않고 비활성으로 둔다. 숨기면 왜 없는지 알 수 없고, 로그인 화면으로
 * 보내면 지도 위치 · 확대 수준 · 필터와 열린 패널이 날아간다 (이슈 #91 계획 「정한 것」).
 *
 * 성공하면 위험도 · 상세 · 등기 쿼리가 무효화되어(queries/risk.ts) 패널이 새 등급을 스스로 다시
 * 그린다. 여기서는 등급이 바뀌었는지만 알린다.
 */
export function ReanalysisButton({ propertyId }: ReanalysisButtonProps) {
  const { isAuthenticated } = useSession();
  const reanalysis = useReanalyzeRisk(propertyId);

  const error = reanalysis.error;
  // 간격 제한은 실패가 아니라 「잠시 뒤 가능」이다 — 명세 1.2. 다음 요청 가능 시각을 함께 낸다
  const isTooSoon = error?.code === RISK_REANALYZE_TOO_SOON;
  const result = reanalysis.data;

  return (
    <div className={styles.action}>
      <Button
        type="button"
        className={styles.button}
        variant="secondary"
        onClick={() => reanalysis.mutate()}
        isLoading={reanalysis.isPending}
        disabled={!isAuthenticated}
      >
        재분석
      </Button>

      {!isAuthenticated && (
        <p className={`${styles.note} type-body-sm`}>로그인하면 재분석을 요청할 수 있습니다.</p>
      )}

      {/* 문구는 서버 error.message 그대로다. 다음 요청 가능 시각만 화면이 덧붙인다 */}
      {isTooSoon && (
        <Alert variant="info">
          {error.message}
          {error.retryAfter !== undefined && ` ${formatDateTime(error.retryAfter)}부터 가능합니다.`}
        </Alert>
      )}

      {error && !isTooSoon && <Alert variant="error">{error.message}</Alert>}

      {result && (
        <Alert variant="info">
          {result.gradeChanged
            ? `등급이 ${riskGradeLabel(result.previousGrade)}에서 ${riskGradeLabel(result.riskGrade)}(으)로 바뀌었습니다.`
            : '등급은 그대로입니다.'}{' '}
          {formatDateTime(result.analyzedAt)} 기준
        </Alert>
      )}
    </div>
  );
}
