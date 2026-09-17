import { Alert, Button, Card } from '../../../components/ui';
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
    <Card className={styles.card}>
      <div className={styles.header}>
        <div>
          <h3 className={`${styles.title} type-body-strong`}>위험도 재분석</h3>
          <p className={`${styles.note} type-caption`}>
            등기와 시세를 다시 확인해 등급을 새로 판정합니다. 관심 매물은 하루 한 번 자동으로 재조회됩니다.
          </p>
        </div>

        <Button
          type="button"
          size="sm"
          variant="secondary"
          onClick={() => reanalysis.mutate()}
          isLoading={reanalysis.isPending}
          disabled={!isAuthenticated}
        >
          재분석
        </Button>
      </div>

      {!isAuthenticated && (
        <p className={`${styles.note} type-caption`}>로그인하면 재분석을 요청할 수 있습니다.</p>
      )}

      {/* 문구는 서버 error.message 그대로다. 다음 요청 가능 시각만 화면이 덧붙인다 */}
      {isTooSoon && (
        <Alert variant="info" className={styles.result}>
          {error.message}
          {error.retryAfter !== undefined && ` ${formatDateTime(error.retryAfter)}부터 가능합니다.`}
        </Alert>
      )}

      {error && !isTooSoon && (
        <Alert variant="error" className={styles.result}>
          {error.message}
        </Alert>
      )}

      {result && (
        <Alert variant="info" className={styles.result}>
          {result.gradeChanged
            ? `등급이 ${riskGradeLabel(result.previousGrade)}에서 ${riskGradeLabel(result.riskGrade)}(으)로 바뀌었습니다.`
            : '등급은 그대로입니다.'}{' '}
          {formatDateTime(result.analyzedAt)} 기준
        </Alert>
      )}
    </Card>
  );
}
