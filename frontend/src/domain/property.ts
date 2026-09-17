// 매물 도메인의 열거값 · 표시 문구 · 상수. 값은 매물 API 명세 1.1 · 1.4 · 1.5와 백엔드 enum의 상수명 그대로다.
// 표시 문구는 여기의 매핑이 갖는다 — 컴포넌트에서 switch · if로 변환하지 않는다 (frontend/CLAUDE.md 타입·열거값).

/** 계약 유형 — 명세 1.1, 백엔드 ContractType */
export const CONTRACT_TYPES = ['DEPOSIT_ONLY', 'MONTHLY_RENT', 'SEMI_DEPOSIT'] as const;
export type ContractType = (typeof CONTRACT_TYPES)[number];

export const CONTRACT_TYPE_LABEL: Record<ContractType, string> = {
  DEPOSIT_ONLY: '전세',
  MONTHLY_RENT: '월세',
  SEMI_DEPOSIT: '반전세',
};

/**
 * 매물 유형 — 백엔드 PropertyType. 적재 경로가 만들어 내는 값만 둔다.
 * 명세 1.1은 「아파트·연립다세대·단독다가구·오피스텔 등」으로 열어 두었으나 지금 붙은 실거래가
 * 서비스가 아파트 · 오피스텔 둘뿐이다. 값이 늘면 백엔드 enum과 함께 여기에 추가한다.
 */
export const PROPERTY_TYPES = ['APARTMENT', 'OFFICETEL'] as const;
export type PropertyType = (typeof PROPERTY_TYPES)[number];

export const PROPERTY_TYPE_LABEL: Record<PropertyType, string> = {
  APARTMENT: '아파트',
  OFFICETEL: '오피스텔',
};

/**
 * 서울시 25개 자치구명. 백엔드 SeoulDistrict의 districtName과 같은 값 · 같은 순서(법정동 코드 순)다.
 * 좌표는 여기 없다 — 지도 상수는 features/property/map/constants.ts가 갖는다 (frontend/CLAUDE.md 지도 상수).
 */
export const SEOUL_DISTRICTS = [
  '종로구',
  '중구',
  '용산구',
  '성동구',
  '광진구',
  '동대문구',
  '중랑구',
  '성북구',
  '강북구',
  '도봉구',
  '노원구',
  '은평구',
  '서대문구',
  '마포구',
  '양천구',
  '강서구',
  '구로구',
  '금천구',
  '영등포구',
  '동작구',
  '관악구',
  '서초구',
  '강남구',
  '송파구',
  '강동구',
] as const;

export type SeoulDistrict = (typeof SEOUL_DISTRICTS)[number];

/**
 * 건축물대장의 해당 여부 표기 — 주거용(isResidential) · 위반건축물(violationBuilding), 명세 1.8.
 * 문구를 컴포넌트에서 삼항으로 다시 만들지 않는다 (frontend/CLAUDE.md 재사용 원칙).
 *
 * 참이 좋은 항목인지(주거용) 나쁜 항목인지(위반건축물)는 표기의 문제가 아니라 강조의 문제이므로
 * 여기 두지 않는다 — 어느 쪽을 강조할지는 그 화면이 정한다.
 */
const APPLICABILITY_LABEL: Record<'applicable' | 'notApplicable', string> = {
  applicable: '해당',
  notApplicable: '해당 없음',
};

export const applicabilityLabel = (isApplicable: boolean) =>
  isApplicable ? APPLICABILITY_LABEL.applicable : APPLICABILITY_LABEL.notApplicable;

/**
 * 목록 정렬 기준 — 명세 1.3이 보증금 · 전세가율 · 등록일 셋으로 정한다. 값의 형태는 명세 1.6 예시의
 * `deposit,asc` 꼴(정렬 필드 + 방향)이고, 필드명은 목록 응답(명세 1.6)의 필드 그대로다.
 *
 * 등록일 내림차순이 여기 없는 이유: 정렬을 지정하지 않을 때 서버가 적용하는 값이다(명세 1.3).
 * 같은 순서를 만드는 선택지를 하나 더 두면 기본값이 두 곳에 생긴다 — 화면은 기본을 「보내지 않음」으로 고른다.
 */
export const PROPERTY_SORTS = [
  'deposit,asc',
  'deposit,desc',
  'debtRatio,asc',
  'debtRatio,desc',
  'registeredAt,asc',
] as const;

export type PropertySort = (typeof PROPERTY_SORTS)[number];

export const PROPERTY_SORT_LABEL: Record<PropertySort, string> = {
  'deposit,asc': '보증금 낮은 순',
  'deposit,desc': '보증금 높은 순',
  'debtRatio,asc': '전세가율 낮은 순',
  'debtRatio,desc': '전세가율 높은 순',
  'registeredAt,asc': '등록일 오래된 순',
};

/**
 * 정렬을 고르지 않았을 때 목록이 놓이는 순서의 문구. 그 순서를 만드는 것은 서버이고
 * 화면은 값을 보내지 않는다 — 명세 1.3 「정렬 기준을 지정하지 않으면 등록일 내림차순」.
 */
export const PROPERTY_SORT_DEFAULT_LABEL = '등록일 최신순';

/**
 * 필터의 보증금 상한 선택지 (원). 명세 1.1의 depositMax에 그대로 들어가는 값이며 목록 자체는
 * 명세가 정하지 않는다 — 화면이 고르는 값이라 도메인 상수로 여기 한 곳에 둔다.
 */
export const DEPOSIT_MAX_OPTIONS: readonly number[] = [
  100_000_000,
  200_000_000,
  300_000_000,
  500_000_000,
];
