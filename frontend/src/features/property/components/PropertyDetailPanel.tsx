import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { ApiError } from '../../../api/client';
import { Alert, Badge, Button, Disclosure, KvRow, KvRowList } from '../../../components/ui';
import { CONTRACT_TYPE_LABEL, PROPERTY_TYPE_LABEL } from '../../../domain/property';
import { debtRatioLabel, priceTypeLabel, riskGradeLabel, riskGradeToken } from '../../../domain/risk';
import { formatDate, formatWon } from '../../../lib/format';
import { propertyQueries } from '../../../queries/property';
import { riskQueries } from '../../../queries/risk';
import { LoanLimitSection } from '../../loan';
import {
  ConsistencyCheck,
  InsuranceProviders,
  PersonalConditions,
  ReanalysisButton,
  RegistryTimeline,
  RiskFindings,
  RiskVerdict,
} from '../../risk';
import { BuildingLedgerSection } from './BuildingLedgerSection';
import styles from './PropertyDetailPanel.module.css';
import { WishlistButton } from './WishlistButton';

/** 분석 이력이 없는 매물은 404로 온다 — 오류가 아니라 정상 상태다 (매물 API 명세 1.4) */
const RISK_NOT_ANALYZED = 'RISK_NOT_ANALYZED';

interface PropertyDetailPanelProps {
  propertyId: number;
  onClose: () => void;
}

/**
 * 지도 옆 상세 패널. 별도 화면으로 가지 않아 지도 위치 · 확대 수준 · 필터가 유지된다
 * (매물 API 명세 1.4). 데이터를 부르는 컴포넌트이고 표시는 표현 컴포넌트에 넘긴다.
 *
 * 배치는 레이아웃 맵 map-search 「detail-panel 내부」다 — 좌우 패딩 24, 섹션 사이를 회색 띠로
 * 가르고, 하단에 {components.action-bar}가 스크롤과 무관하게 고정된다. 섹션 묶음은 여기가
 * 감싸고(.section), 안쪽 내용만 각 기능 컴포넌트가 갖는다.
 */
export function PropertyDetailPanel({ propertyId, onClose }: PropertyDetailPanelProps) {
  const detailQuery = useQuery(propertyQueries.detail(propertyId));
  const riskQuery = useQuery(riskQueries.analysis(propertyId));
  // 건축물대장(PROP-04)과 등기 이력(RISK-07)은 펼칠 때 조회한다 — 상세 진입 시 호출은 매물 상세와
  // 위험도 둘이다 (명세 1.4 「탐색 동작과 호출 시점」). 열림 여부를 여기가 갖고 자식을 그때 마운트한다
  //
  // 둘은 각자 열고 닫는다. 하나를 열 때 다른 하나를 닫지 않는 이유: 명의 · 문서 정합(RISK-04)이
  // 대조하는 것이 대장 면적과 등기 표제부 면적인데 패널의 ConsistencyCheck는 그 대조 「결과」만
  // 보여준다. 결과를 의심하는 사용자가 볼 것은 두 원본이므로 나란히 두고 볼 수 있어야 한다.
  // 패널 .body에 overflow-y: auto가 있어 패널이 길어지는 것은 문제가 되지 않는다.
  const [isLedgerOpen, setIsLedgerOpen] = useState(false);
  const [isRegistryOpen, setIsRegistryOpen] = useState(false);

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
        {detailQuery.isPending && (
          <div className={styles.section}>
            <p className="type-body">불러오는 중입니다.</p>
          </div>
        )}

        {detailQuery.error && (
          <div className={styles.section}>
            <Alert variant="error">{detailQuery.error.message}</Alert>
          </div>
        )}

        {detail && (
          <>
            <div className={styles.section}>
              <div className={styles.titleRow}>
                <Badge variant={riskGradeToken(detail.riskSummary?.riskGrade ?? null)}>
                  {riskGradeLabel(detail.riskSummary?.riskGrade ?? null)}
                </Badge>
                <span className="type-body-sm">{PROPERTY_TYPE_LABEL[detail.propertyType] ?? detail.propertyType}</span>
              </div>

              <p className={`${styles.deposit} type-heading-1`}>{formatWon(detail.deposit)}</p>
              <p className={`${styles.address} type-body`}>{detail.address}</p>

              <KvRowList>
                <KvRow label="계약유형">{CONTRACT_TYPE_LABEL[detail.contractType]}</KvRow>
                {detail.monthlyRent > 0 && <KvRow label="월세">{formatWon(detail.monthlyRent)}</KvRow>}
                <KvRow label="전용면적">{detail.areaSqm}㎡</KvRow>
                <KvRow label="층">{detail.floor}층</KvRow>
                <KvRow label="임대인">{detail.landlordName}</KvRow>
                {/* 시세는 산출 근거와 기준일을 함께 적는다 (PROP-03 · 매물 명세 1.7). 미분석 매물은
                    위험도 응답이 없으므로 여기가 근거를 보여 주는 유일한 자리다 */}
                <KvRow label="시세">
                  {formatWon(detail.marketPrice)}
                  <span className={`${styles.source} type-body-sm`}>
                    {priceTypeLabel(detail.priceType)} · {formatDate(detail.priceDate)} 기준
                  </span>
                </KvRow>
                {/* 미분석이면 riskSummary 자체가 null이다 — 문구는 domain/risk.ts가 갖는다 */}
                <KvRow label="전세가율">{debtRatioLabel(detail.riskSummary?.debtRatio)}</KvRow>
                <KvRow label="등록일">{formatDate(detail.registeredAt)}</KvRow>
              </KvRowList>
            </div>

            {riskQuery.isPending && (
              <div className={styles.section}>
                <p className="type-body">위험도를 불러오는 중입니다.</p>
              </div>
            )}

            {notAnalyzedMessage !== null && (
              <div className={styles.section}>
                <Alert variant="info">{notAnalyzedMessage} 기본 정보만 표시합니다.</Alert>
              </div>
            )}

            {riskError && notAnalyzedMessage === null && (
              <div className={styles.section}>
                <Alert variant="error">{riskError.message}</Alert>
              </div>
            )}

            {riskQuery.data && (
              <>
                <div className={styles.section}>
                  <RiskVerdict analysis={riskQuery.data} />
                </div>
                <div className={styles.section}>
                  <InsuranceProviders providers={riskQuery.data.providers} />
                </div>
                <div className={styles.section}>
                  <RiskFindings rightViolations={riskQuery.data.rightViolations} warnings={riskQuery.data.warnings} />
                </div>
                <div className={styles.section}>
                  <ConsistencyCheck consistency={riskQuery.data.consistency} />
                </div>
                <div className={styles.section}>
                  <PersonalConditions conditions={riskQuery.data.personalConditions} />
                </div>
              </>
            )}

            {/* 원본 자료는 위험도 분석 여부와 무관하다 — 미분석 매물에서도 보인다 */}
            <div className={styles.section}>
              <Disclosure
                title="건축물대장"
                isOpen={isLedgerOpen}
                onToggle={() => setIsLedgerOpen((isOpen) => !isOpen)}
              >
                <BuildingLedgerSection propertyId={propertyId} />
              </Disclosure>
            </div>

            <div className={styles.section}>
              <Disclosure
                title="등기 이력"
                isOpen={isRegistryOpen}
                onToggle={() => setIsRegistryOpen((isOpen) => !isOpen)}
              >
                <RegistryTimeline propertyId={propertyId} />
              </Disclosure>
            </div>

            {/* 대출 한도(LOAN-01). 위험도 분석 여부와 무관하게 마운트한다 — 가입 불가 매물은
                422 LOAN_PROPERTY_NOT_ELIGIBLE 안내가 그 안에서 나온다 */}
            <div className={styles.section}>
              <LoanLimitSection propertyId={propertyId} />
            </div>
          </>
        )}
      </div>

      {/*
        {components.action-bar} — 패널 스크롤과 무관하게 같은 자리에 있는 하단 고정 바 (레이아웃 맵
        map-search 11번). 재분석(RISK-08)과 관심 등록 · 해제(PROP-05)가 이 패널의 두 동작이다.
        등록 여부는 상세 응답의 wishlisted다 — 관심 매물 목록을 따로 받아 계산하지 않는다.
      */}
      {detail && (
        <div className={styles.actionBar}>
          <ReanalysisButton propertyId={propertyId} />
          <WishlistButton propertyId={propertyId} isWishlisted={detail.wishlisted} />
        </div>
      )}
    </aside>
  );
}
