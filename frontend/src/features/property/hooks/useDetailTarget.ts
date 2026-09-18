import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../api/client';
import { propertyQueries } from '../../../queries/property';

/** 없는 매물 — 공통 규약 오류 코드 표 (404) */
const PROPERTY_NOT_FOUND = 'PROPERTY_NOT_FOUND';

/** 열 매물이 없을 때 쿼리 정의에 넘기는 자리값. enabled가 거짓이라 요청되지 않는다 */
const NO_PROPERTY = 0;

export interface DetailTarget {
  /** 상세 응답의 자치구. 아직 도착하지 않았거나 실패면 null */
  district: string | null;
  /** 없는 매물이면 서버 error.message 그대로. 그 밖의 실패는 여기서 가르지 않는다 */
  notFoundMessage: string | null;
}

/**
 * URL이 가리키는 매물의 상세에서 **지도 화면이 쓰는 것만** 뽑는다 — 어느 자치구인지와, 없는
 * 매물인지다. 패널이 부르는 것과 같은 쿼리 정의(propertyQueries.detail)라 키가 같고, 관측자가
 * 둘이어도 요청은 하나다 — 새 쿼리 키를 만들지 않는다.
 *
 * 지도 단계를 매물의 자치구로 맞추려면 그 자치구 이름이 있어야 하는데 상세 응답에 district가
 * 있으므로 추가 조회가 필요 없다 (매물 API 명세 1.4).
 *
 * 404만 가른다 — 「그 번호의 매물이 없다」는 링크 자체가 잘못됐다는 뜻이라 화면이 파라미터를
 * 지우는 근거가 되지만, 네트워크 · 5xx는 지금 못 불러온 것이라 같은 처리를 하면 사용자가
 * 가리키던 매물을 잃는다. 그 문구는 패널이 제 자리에서 보여준다.
 */
export function useDetailTarget(propertyId: number | null): DetailTarget {
  const detailQuery = useQuery({
    ...propertyQueries.detail(propertyId ?? NO_PROPERTY),
    enabled: propertyId !== null,
  });

  const error = detailQuery.error as ApiError | null;

  return {
    district: detailQuery.data?.district ?? null,
    notFoundMessage: error?.code === PROPERTY_NOT_FOUND ? error.message : null,
  };
}
