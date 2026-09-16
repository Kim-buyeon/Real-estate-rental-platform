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
 * 필터의 보증금 상한 선택지 (원). 명세 1.1의 depositMax에 그대로 들어가는 값이며 목록 자체는
 * 명세가 정하지 않는다 — 화면이 고르는 값이라 도메인 상수로 여기 한 곳에 둔다.
 */
export const DEPOSIT_MAX_OPTIONS: readonly number[] = [
  100_000_000,
  200_000_000,
  300_000_000,
  500_000_000,
];
