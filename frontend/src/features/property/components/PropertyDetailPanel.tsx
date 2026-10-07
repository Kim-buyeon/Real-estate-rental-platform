import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { ApiError } from '../../../api/client';
import { Alert, Badge, Button, Disclosure, KvRow, KvRowList, Spinner } from '../../../components/ui';
import { contractTypeLabel, propertyTypeLabel } from '../../../domain/property';
import {
  debtRatioLabel,
  LANDLORD_NAME_SOURCE_NOTE,
  priceTypeLabel,
  riskGradeLabel,
  riskGradeToken,
} from '../../../domain/risk';
import { formatDate, formatWon } from '../../../lib/format';
import { propertyQueries } from '../../../queries/property';
import { riskQueries, useReanalyzeRisk } from '../../../queries/risk';
import { useSession } from '../../../session/useSession';
import { LoanLimitSection } from '../../loan';
import {
  ConsistencyCheck,
  InsuranceProviders,
  PersonalConditions,
  ReanalysisButton,
  ReanalysisNotice,
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
  // 재분석 요청 하나를 바 안의 버튼과 바 밖의 결과 안내가 함께 읽는다 — 그래서 여기서 한 번 만든다
  const reanalysis = useReanalyzeRisk(propertyId);
  const { isAuthenticated } = useSession();

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
            <Spinner />
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
                <span className="type-body-sm">{propertyTypeLabel(detail.propertyType)}</span>
              </div>

              <p className={`${styles.deposit} type-heading-1`}>{formatWon(detail.deposit)}</p>
              <p className={`${styles.address} type-body`}>{detail.address}</p>

              <KvRowList>
                <KvRow label="계약유형">{contractTypeLabel(detail.contractType)}</KvRow>
                {detail.monthlyRent > 0 && <KvRow label="월세">{formatWon(detail.monthlyRent)}</KvRow>}
                <KvRow label="전용면적">{detail.areaSqm}㎡</KvRow>
                <KvRow label="층">{detail.floor}층</KvRow>
                {/* 임대인은 아직 예시 등기와 대조되는 값이다 — 시세 행의 출처 문구와 같은 방식으로 알린다 */}
                <KvRow label="임대인">
                  {detail.landlordName}
                  <span className={`${styles.source} type-body-sm`}>{LANDLORD_NAME_SOURCE_NOTE}</span>
                </KvRow>
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
                <Spinner label="위험 등급을 불러오는 중" />
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

            {/* 원본 자료는 위험 등급 분석 여부와 무관하다 — 미분석 매물에서도 보인다 */}
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

            {/* 대출 한도(LOAN-01). 위험 등급 분석 여부와 무관하게 마운트한다 — 가입 불가 매물은
                422 LOAN_PROPERTY_NOT_ELIGIBLE 안내가 그 안에서 나온다 */}
            <div className={styles.section}>
              <LoanLimitSection propertyId={propertyId} />
            </div>

            {/*
              하단 바 두 동작의 안내. 바는 높이 92 고정이라 안내를 담지 않고 본문 끝에 낸다 — 낼 것이 없으면
              이 자리는 비어 접힌다(.notices:empty). 비로그인 사유는 두 동작이 같아 한 문장으로 낸다
            */}
            <div className={styles.notices}>
              {!isAuthenticated && (
                <p className={`${styles.note} type-body-sm`}>
                  로그인하면 재분석을 요청하고 관심 매물로 등록할 수 있습니다.
                </p>
              )}
              <ReanalysisNotice reanalysis={reanalysis} />
            </div>
          </>
        )}
      </div>

      {/*
        {components.action-bar} — 패널 스크롤과 무관하게 같은 자리에 있는 하단 고정 바 (레이아웃 맵
        map-search 11번). 정의서 7절은 {components.button-icon} 2개 + {components.button-primary}(나머지 폭)다.
        이 패널에서 서버에 요청하는 동작은 관심 등록 · 해제(PROP-05)와 재분석(RISK-08) 둘이다 — 관심은 정의서가
        button-icon 의 쓰임으로 적은 「찜」이라 아이콘, 남은 재분석이 primary 다. 정의서의 다른 아이콘 자리(공유)는
        기능 정의에 없는 기능이라 만들지 않는다. 등록 여부는 상세 응답의 wishlisted다 — 관심 매물 목록을 따로
        받아 계산하지 않는다.

        토스트가 바를 가리지 않게 data-toast-avoid="action-bar" 를 단다 — 토스트는 바 위로 올라선다(정의서 K-19,
        Toast.module.css)
      */}
      {detail && (
        <div className={styles.actionBar} role="group" aria-label="매물 동작" data-toast-avoid="action-bar">
          <WishlistButton propertyId={propertyId} isWishlisted={detail.wishlisted} />
          <ReanalysisButton reanalysis={reanalysis} className={styles.primaryAction} />
        </div>
      )}
    </aside>
  );
}
