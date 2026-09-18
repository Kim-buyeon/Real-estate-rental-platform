import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import type { ApiError } from '../../../api/client';
import { Alert, Badge, buttonClassName } from '../../../components/ui';
import {
  appliedRegulationLabel,
  APPLIED_REGULATION_LABEL,
  DTI_REFERENCE_LABEL,
  LOAN_PROPERTY_NOT_ELIGIBLE,
  PROFILE_INCOMPLETE,
  STRESS_DSR_LABEL,
  type AppliedRegulation,
} from '../../../domain/loan';
import { profileFieldLabel } from '../../../domain/user';
import { formatPercent, formatWon } from '../../../lib/format';
import { loanQueries } from '../../../queries/loan';
import { useSession } from '../../../session/useSession';
import styles from './LoanLimitSection.module.css';

interface LoanLimitSectionProps {
  propertyId: number;
}

/** 한도 항목 한 줄. 금액이 있는 항목만 만든다 — 값이 null인 규제는 줄을 만들지 않는다 */
interface LimitRow {
  regulation: AppliedRegulation;
  amount: number;
}

/**
 * 대출 한도 (LOAN-01). 서버가 계산한 항목별 한도 · 최종 한도 · 결정 항목을 표시만 한다 —
 * 최솟값을 다시 고르거나 임계값(비율 · 상한 · 스트레스 금리)을 화면에 적지 않는다
 * (frontend/CLAUDE.md 상태 「화면에서 판정하지 않는다」). 기준값은 서버(LOAN_REGULATION)가 갖는다.
 *
 * 데이터를 부르는 컴포넌트다. 패널이 열릴 때 함께 조회한다 — 「안전 판정을 받은 매물에는 대출 한도를
 * 함께 제시한다」가 이 서비스의 목적이라 건축물대장 · 등기 이력처럼 펼칠 때로 미루지 않는다.
 *
 * 세 갈래로 갈린다.
 *  - 422 PROFILE_INCOMPLETE — 사용자가 고칠 수 있고 고칠 화면(USER-03)이 있으므로 오류가 아니라
 *    안내 + 자격 정보 링크다. 문구는 서버 error.message 그대로이고 링크만 화면이 붙인다.
 *  - 422 LOAN_PROPERTY_NOT_ELIGIBLE — 「가입이 불가한 매물에는 한도를 제시하지 않는다」가 설계이지
 *    실패가 아니다. 상세 패널의 RISK_NOT_ANALYZED와 같은 판단으로 info다.
 *  - 정상 — 항목별 한도 · 최종 한도 · 결정 항목 강조 · 참고 둘.
 */
export function LoanLimitSection({ propertyId }: LoanLimitSectionProps) {
  const { isAuthenticated } = useSession();
  // 인증 「필수」다 — 비로그인에서는 401이 될 요청을 보내지 않는다. 키는 쿼리 팩토리가 만든다
  const limitQuery = useQuery({ ...loanQueries.limit(propertyId), enabled: isAuthenticated });

  // client가 어떤 실패든 ApiError로 바꿔 던진다(api/client.ts). code로 갈라야 해 좁힌다
  const error = limitQuery.error as ApiError | null;
  const limit = limitQuery.data;

  return (
    <section className={styles.section} aria-label="대출 한도">
      <h3 className={`${styles.title} type-heading-3`}>대출 한도</h3>

      {!isAuthenticated && (
        // 비활성 버튼이 아니라 안내다 — 자격 정보를 입력한 사용자에게만 계산되는 값이라
        // 무엇이 막혔는지보다 무엇이 필요한지를 알려야 한다. 로그인 화면으로 보내는 링크는 두지
        // 않는다 — 지도 위치 · 확대 수준 · 필터와 열린 패널이 날아간다(WishlistButton과 같은 판단).
        // 자격 정보 링크는 그렇지 않다: 그 화면에 가야 고칠 수 있는 상태라 이동이 목적이다.
        // Alert이 아니라 한 줄 안내다 — 조회 실패도 빈 상태도 아니고, 같은 패널의 관심 매물
        // 비로그인 안내와 같은 형태여야 한 화면에 두 가지 안내 모양이 생기지 않는다
        <p className={`${styles.note} type-caption`}>
          로그인하면 이 매물의 전세자금대출 한도를 계산해 드립니다.
        </p>
      )}

      {isAuthenticated && limitQuery.isPending && <p className="type-caption">한도를 불러오는 중입니다.</p>}

      {error?.code === PROFILE_INCOMPLETE && (
        <div className={styles.notice}>
          {/* 문구는 서버 error.message 그대로다. 어느 항목이 비었는지는 error.field가 알려 준다 */}
          <Alert variant="info">{error.message}</Alert>
          {error.field !== undefined && (
            <p className={`${styles.note} type-caption`}>미입력 항목 · {profileFieldLabel(error.field)}</p>
          )}
          <Link to="/me/profile" className={buttonClassName('primary', 'sm')}>
            자격 정보 입력
          </Link>
        </div>
      )}

      {error?.code === LOAN_PROPERTY_NOT_ELIGIBLE && <Alert variant="info">{error.message}</Alert>}

      {error !== null && error.code !== PROFILE_INCOMPLETE && error.code !== LOAN_PROPERTY_NOT_ELIGIBLE && (
        <Alert variant="error">{error.message}</Alert>
      )}

      {limit && (
        <>
          <div className={styles.final}>
            <p className={`${styles.finalLabel} type-caption`}>최종 한도</p>
            <p className={`${styles.finalAmount} type-heading-3`}>{formatWon(limit.finalLimit)}</p>
            {/* 어느 항목이 한도를 잡았는지가 이 화면의 핵심 정보다. 조사를 붙이지 않는다 —
                항목 이름마다 조사가 갈려(한도가 · 상한이) 문구를 코드로 고르게 된다 */}
            <p className={`${styles.finalReason} type-caption`}>
              결정 항목 · {appliedRegulationLabel(limit.appliedRegulation)}
            </p>
          </div>

          <dl className={`${styles.rows} type-caption`}>
            {limitRowsOf(limit.depositLimit, limit.guaranteeCapLimit, limit.dsrLimit).map((row) => {
              const isApplied = row.regulation === limit.appliedRegulation;
              return (
                <div
                  key={row.regulation}
                  className={isApplied ? `${styles.row} ${styles.applied}` : styles.row}
                >
                  <dt className={styles.rowLabel}>
                    {APPLIED_REGULATION_LABEL[row.regulation]}
                    {isApplied && <Badge variant="primary">결정</Badge>}
                  </dt>
                  <dd className={styles.rowValue}>{formatWon(row.amount)}</dd>
                </div>
              );
            })}
          </dl>

          {/* 무주택이면 DSR 한도가 null이라 위 줄에 없다 — 「해당 없음」으로 적지 않고 왜 없는지를 적는다.
              DSR은 적용되지 않는 규제이지 값이 빈 항목이 아니다 */}
          {limit.dsrLimit === null && (
            <p className={`${styles.note} type-caption`}>
              DSR은 주택 보유자에게 적용되는 규제입니다. 이 한도에는 DSR 기준이 반영되지 않았습니다.
            </p>
          )}

          {/* 성공 응답에서는 빈 배열이다(명세 1.1). 서버가 담아 보내면 무엇이 비었는지 그대로 보여준다 */}
          {limit.missingFields.length > 0 && (
            <p className={`${styles.note} type-caption`}>
              미입력 항목 · {limit.missingFields.map(profileFieldLabel).join(' · ')}
            </p>
          )}

          {/* 참고 수치는 위 한도 항목과 시각적으로 가른다 — 나란히 두면 최종 한도에 반영된 것으로 읽힌다.
              스트레스 금리 적용 한도는 전세대출 이자분 적용 여부가 미확정이라 병기만 한다
              (비즈니스 로직 6장 「참고 병기 — 최종 한도에 넣지 않는다」) */}
          {(limit.stressDsrLimit !== null || limit.dtiReference !== null) && (
            <section className={styles.reference} aria-label="참고 수치">
              <p className={`${styles.referenceTitle} type-caption`}>참고 — 최종 한도에 반영하지 않은 수치</p>

              <dl className={`${styles.rows} type-caption`}>
                {limit.stressDsrLimit !== null && (
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>{STRESS_DSR_LABEL}</dt>
                    <dd className={styles.rowValue}>{formatWon(limit.stressDsrLimit)}</dd>
                  </div>
                )}
                {limit.dtiReference !== null && (
                  <div className={styles.row}>
                    <dt className={styles.rowLabel}>{DTI_REFERENCE_LABEL}</dt>
                    <dd className={styles.rowValue}>{formatPercent(limit.dtiReference)}</dd>
                  </div>
                )}
              </dl>

              {limit.dtiReference !== null && (
                <p className={`${styles.note} type-caption`}>
                  DTI는 한도 판정에 쓰지 않는 참고 수치입니다. DSR은 전체 대출의 원리금을, DTI는
                  주택담보대출 원리금과 기타 대출 이자를 소득과 견줍니다.
                </p>
              )}
            </section>
          )}
        </>
      )}
    </section>
  );
}

/**
 * 표시할 한도 줄을 만든다. 값이 null인 DSR은 줄을 만들지 않는다.
 *
 * 상품 한도(PRODUCT_LIMIT)는 명세 1.1에 금액 필드가 없어 줄이 없다 — 그 항목이 결정했을 때는
 * 위 최종 한도 블록의 결정 항목 문구가 그 사실을 보여준다. 금액을 finalLimit에서 추론하지 않는다.
 */
function limitRowsOf(depositLimit: number, guaranteeCapLimit: number, dsrLimit: number | null): LimitRow[] {
  const rows: LimitRow[] = [
    { regulation: 'DEPOSIT_RATIO', amount: depositLimit },
    { regulation: 'GUARANTEE_CAP', amount: guaranteeCapLimit },
  ];
  if (dsrLimit !== null) rows.push({ regulation: 'DSR', amount: dsrLimit });
  return rows;
}
