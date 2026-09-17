import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../api/client';
import { Alert, Badge, Button, Card } from '../../../components/ui';
import { CONTRACT_TYPE_LABEL, PROPERTY_TYPE_LABEL } from '../../../domain/property';
import { debtRatioLabel, priceTypeLabel, riskGradeLabel, riskGradeToken } from '../../../domain/risk';
import { formatDate, formatWon } from '../../../lib/format';
import { propertyQueries } from '../../../queries/property';
import { riskQueries } from '../../../queries/risk';
import { ConsistencyCheck, InsuranceProviders, PersonalConditions, RiskFindings, RiskVerdict } from '../../risk';
import styles from './PropertyDetailPanel.module.css';

/** 분석 이력이 없는 매물은 404로 온다 — 오류가 아니라 정상 상태다 (매물 API 명세 1.4) */
const RISK_NOT_ANALYZED = 'RISK_NOT_ANALYZED';

interface PropertyDetailPanelProps {
  propertyId: number;
  onClose: () => void;
}

/**
 * 지도 옆 상세 패널. 별도 화면으로 가지 않아 지도 위치 · 확대 수준 · 필터가 유지된다
 * (매물 API 명세 1.4). 데이터를 부르는 컴포넌트이고 표시는 표현 컴포넌트에 넘긴다.
 */
export function PropertyDetailPanel({ propertyId, onClose }: PropertyDetailPanelProps) {
  const detailQuery = useQuery(propertyQueries.detail(propertyId));
  const riskQuery = useQuery(riskQueries.analysis(propertyId));

  const detail = detailQuery.data;
  const riskError = riskQuery.error as ApiError | null;
  // 미분석 안내의 본문은 서버 error.message 그대로다 — 코드별 문구를 프론트에 다시 적지 않는다
  // (frontend/CLAUDE.md API 클라이언트). 화면 사정(기본 정보만 보인다)만 아래에서 덧붙인다.
  const notAnalyzedMessage = riskError?.code === RISK_NOT_ANALYZED ? riskError.message : null;

  return (
    <aside className={styles.panel} aria-label="매물 상세">
      <div className={styles.header}>
        <h2 className="type-heading-3">매물 상세</h2>
        <Button type="button" variant="ghost" size="sm" onClick={onClose} aria-label="상세 닫기">
          ✕
        </Button>
      </div>

      <div className={styles.body}>
        {detailQuery.isPending && <p className="type-body">불러오는 중입니다.</p>}

        {detailQuery.error && <Alert variant="error">{detailQuery.error.message}</Alert>}

        {detail && (
          <>
            <Card className={styles.section}>
              <div className={styles.titleRow}>
                <Badge variant={riskGradeToken(detail.riskSummary?.riskGrade ?? null)}>
                  {riskGradeLabel(detail.riskSummary?.riskGrade ?? null)}
                </Badge>
                <span className="type-caption">{PROPERTY_TYPE_LABEL[detail.propertyType] ?? detail.propertyType}</span>
              </div>

              <p className={`${styles.address} type-body-strong`}>{detail.address}</p>

              <dl className={`${styles.facts} type-caption`}>
                <div className={styles.fact}>
                  <dt>계약유형</dt>
                  <dd>{CONTRACT_TYPE_LABEL[detail.contractType]}</dd>
                </div>
                <div className={styles.fact}>
                  <dt>보증금</dt>
                  <dd>{formatWon(detail.deposit)}</dd>
                </div>
                {detail.monthlyRent > 0 && (
                  <div className={styles.fact}>
                    <dt>월세</dt>
                    <dd>{formatWon(detail.monthlyRent)}</dd>
                  </div>
                )}
                <div className={styles.fact}>
                  <dt>전용면적</dt>
                  <dd>{detail.areaSqm}㎡</dd>
                </div>
                <div className={styles.fact}>
                  <dt>층</dt>
                  <dd>{detail.floor}층</dd>
                </div>
                <div className={styles.fact}>
                  <dt>임대인</dt>
                  <dd>{detail.landlordName}</dd>
                </div>
                {/* 시세는 산출 근거와 기준일을 함께 적는다 (PROP-03 · 매물 명세 1.7). 미분석 매물은
                    위험도 응답이 없으므로 여기가 근거를 보여 주는 유일한 자리다 */}
                <div className={styles.fact}>
                  <dt>시세</dt>
                  <dd>
                    {formatWon(detail.marketPrice)}
                    <span className={styles.source}>
                      {priceTypeLabel(detail.priceType)} · {formatDate(detail.priceDate)} 기준
                    </span>
                  </dd>
                </div>
                <div className={styles.fact}>
                  <dt>전세가율</dt>
                  {/* 미분석이면 riskSummary 자체가 null이다 — 문구는 domain/risk.ts가 갖는다 */}
                  <dd>{debtRatioLabel(detail.riskSummary?.debtRatio)}</dd>
                </div>
                <div className={styles.fact}>
                  <dt>등록일</dt>
                  <dd>{formatDate(detail.registeredAt)}</dd>
                </div>
              </dl>
            </Card>

            {riskQuery.isPending && <p className="type-body">위험도를 불러오는 중입니다.</p>}

            {notAnalyzedMessage !== null && (
              <Alert variant="info">{notAnalyzedMessage} 기본 정보만 표시합니다.</Alert>
            )}

            {riskError && notAnalyzedMessage === null && <Alert variant="error">{riskError.message}</Alert>}

            {riskQuery.data && (
              <>
                <RiskVerdict analysis={riskQuery.data} />
                <InsuranceProviders providers={riskQuery.data.providers} />
                <RiskFindings
                  rightViolations={riskQuery.data.rightViolations}
                  warnings={riskQuery.data.warnings}
                />
                <ConsistencyCheck consistency={riskQuery.data.consistency} />
                <PersonalConditions conditions={riskQuery.data.personalConditions} />
              </>
            )}

            {/* 건축물대장(PROP-04) · 대출 한도(LOAN-01) · 재분석(RISK-08) · 관심 등록(PROP-05)은 다음 슬라이스가 여기에 붙인다 */}
          </>
        )}
      </div>
    </aside>
  );
}
