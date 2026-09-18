import { memo } from 'react';
import type { InsuranceProvider } from '../../../api/risk';
import { Badge } from '../../../components/ui';
import { guaranteeFailedConditionLabel, guaranteeProviderLabel } from '../../../domain/risk';
import { formatWon } from '../../../lib/format';
import styles from './InsuranceProviders.module.css';

export interface InsuranceProvidersProps {
  providers: InsuranceProvider[];
}

/**
 * 보증보험 3사 판정 (RISK-05). 응답이 주는 기관을 그대로 모두 나열한다 — 가입 가능한 기관만
 * 추리지 않는다. 왜 가입할 수 없는지가 이 서비스의 핵심 정보다 (PROP-03 계획 「3사 판정 표시」).
 * 가입 가능 여부 · 보증한도는 서버 값이며 화면에서 다시 계산하지 않는다.
 *
 * 상세 패널의 한 섹션이다 — 좌우 패딩과 섹션 사이 띠는 패널이 갖는다.
 */
export const InsuranceProviders = memo(function InsuranceProviders({ providers }: InsuranceProvidersProps) {
  return (
    <section aria-label="보증보험 가입 판정">
      <h3 className={`${styles.title} type-heading-3`}>보증보험 가입 판정</h3>

      <ul className={styles.list}>
        {providers.map((provider) => (
          <li key={provider.provider} className={styles.item}>
            <div className={styles.header}>
              <span className="type-body-strong">{guaranteeProviderLabel(provider.provider)}</span>
              <span className={styles.marks}>
                {provider.loanLinkRequired && <Badge variant="neutral">대출 연계 필요</Badge>}
                <Badge variant={provider.eligible ? 'primary' : 'neutral'}>
                  {provider.eligible ? '가입 가능' : '가입 불가'}
                </Badge>
              </span>
            </div>

            <dl className={`${styles.facts} type-caption`}>
              <div className={styles.fact}>
                <dt>보증한도</dt>
                <dd>{formatWon(provider.guaranteeLimit)}</dd>
              </div>
              {provider.estimatedPremium !== null && (
                <div className={styles.fact}>
                  <dt>예상 보증료</dt>
                  <dd>{formatWon(provider.estimatedPremium)}</dd>
                </div>
              )}
              {provider.productName !== null && (
                <div className={styles.fact}>
                  <dt>보증 상품</dt>
                  <dd>{provider.productName}</dd>
                </div>
              )}
            </dl>

            {provider.failedConditions.length > 0 && (
              <div className={styles.failed}>
                <p className={`${styles.failedTitle} type-caption`}>가입 불가 사유</p>
                <ul className={`${styles.failedList} type-caption`}>
                  {provider.failedConditions.map((condition) => (
                    <li key={condition}>{guaranteeFailedConditionLabel(condition)}</li>
                  ))}
                </ul>
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
});
