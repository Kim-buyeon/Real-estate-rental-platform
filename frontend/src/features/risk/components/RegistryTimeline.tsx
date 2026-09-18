import { useQuery } from '@tanstack/react-query';
import { Alert } from '../../../components/ui';
import { formatDateTime } from '../../../lib/format';
import { riskQueries } from '../../../queries/risk';
import { MortgageRow } from './MortgageRow';
import { OwnershipRow } from './OwnershipRow';
import styles from './RegistryTimeline.module.css';

interface RegistryTimelineProps {
  propertyId: number;
}

/**
 * 등기 이력 (RISK-07). 판정 근거가 아니라 원본 자료다.
 *
 * 데이터를 부르는 컴포넌트다 — 부모가 펼칠 때 비로소 마운트하므로 여기서 쿼리를 부르는 것이 곧
 * 「펼칠 때 조회」다. 「매물마다 건수 편차가 크므로 별도 엔드포인트로 분리한다」(위험도 명세 1장).
 * 한 줄의 표시는 표현 컴포넌트(OwnershipRow · MortgageRow)가 갖는다.
 *
 * 갑구와 을구를 한 목록으로 합치지 않는다 — 순위번호가 구별로 매겨져 섞으면 의미가 깨진다.
 * 서버가 접수일 오름차순(같으면 순위번호 순)으로 주므로 화면에서 다시 정렬하지 않는다(명세 1.3).
 */
export function RegistryTimeline({ propertyId }: RegistryTimelineProps) {
  const registryQuery = useQuery(riskQueries.registry(propertyId));

  if (registryQuery.isPending) {
    return <p className="type-caption">불러오는 중입니다.</p>;
  }

  if (registryQuery.error) {
    return <Alert variant="error">{registryQuery.error.message}</Alert>;
  }

  const registry = registryQuery.data;

  return (
    <>
      <p className={`${styles.note} type-body-sm`}>접수일이 우선변제 순서를 정합니다.</p>

      <section className={styles.section}>
        <h4 className={`${styles.sectionTitle} type-label`}>갑구 · 소유권</h4>
        {registry.ownerships.length === 0 ? (
          <p className={`${styles.empty} type-body`}>없음</p>
        ) : (
          <ol className={`${styles.list} type-body`}>
            {registry.ownerships.map((ownership) => (
              <OwnershipRow key={`${ownership.rankNo}-${ownership.receivedDate}`} ownership={ownership} />
            ))}
          </ol>
        )}
      </section>

      <section className={styles.section}>
        <h4 className={`${styles.sectionTitle} type-label`}>을구 · 근저당</h4>
        {registry.mortgages.length === 0 ? (
          <p className={`${styles.empty} type-body`}>없음</p>
        ) : (
          <ol className={`${styles.list} type-body`}>
            {registry.mortgages.map((mortgage) => (
              <MortgageRow key={`${mortgage.rankNo}-${mortgage.receivedDate}`} mortgage={mortgage} />
            ))}
          </ol>
        )}
      </section>

      <p className={`${styles.collectedAt} type-body-sm`}>등기 수집 {formatDateTime(registry.collectedAt)}</p>
    </>
  );
}
