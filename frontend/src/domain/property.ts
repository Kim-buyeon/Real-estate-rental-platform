// 매물 도메인의 열거값 · 표시 문구 · 상수. 값은 매물 API 명세 1.1 · 1.4 · 1.5와 백엔드 enum의 상수명 그대로다.
// 표시 문구는 여기의 매핑이 갖는다 — 컴포넌트에서 switch · if로 변환하지 않는다 (frontend/CLAUDE.md 타입·열거값).

/** 계약 유형 — 명세 1.1, 백엔드 ContractType */
export const CONTRACT_TYPES = ['DEPOSIT_ONLY', 'MONTHLY_RENT', 'SEMI_DEPOSIT'] as const;
export type ContractType = (typeof CONTRACT_TYPES)[number];

/** 밖으로는 contractTypeLabel()만 낸다 — 매핑을 직접 인덱싱하면 모르는 코드 처리가 호출부마다 갈린다 */
const CONTRACT_TYPE_LABEL: Record<ContractType, string> = {
  DEPOSIT_ONLY: '전세',
  MONTHLY_RENT: '월세',
  SEMI_DEPOSIT: '반전세',
};

/**
 * 매물 유형 — 백엔드 PropertyType. 적재 경로가 만들어 내는 값만 둔다.
 * 명세 1.1과 같이 지금 붙은 실거래가 서비스가 아파트 · 오피스텔 둘뿐이다.
 * 값이 늘면 백엔드 enum · 명세와 함께 여기에 추가한다.
 */
export const PROPERTY_TYPES = ['APARTMENT', 'OFFICETEL'] as const;
export type PropertyType = (typeof PROPERTY_TYPES)[number];

/** 밖으로는 propertyTypeLabel()만 낸다 — 위 CONTRACT_TYPE_LABEL과 같은 이유다 */
const PROPERTY_TYPE_LABEL: Record<PropertyType, string> = {
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
 * 쓰는 쪽은 매물 하나다 — BuildingLedgerSection. 위험도 판정 항목의 같은 표기는 domain/risk.ts가
 * 따로 갖는다(그쪽 주석에 모으지 않는 이유를 적었다).
 *
 * 참이 좋은 항목인지(주거용) 나쁜 항목인지(위반건축물)는 표기의 문제가 아니라 강조의 문제이므로
 * 여기 두지 않는다 — 어느 쪽을 강조할지는 그 화면이 정한다.
 */
const APPLICABILITY_LABEL: Record<'applicable' | 'notApplicable', string> = {
  applicable: '해당',
  notApplicable: '해당 없음',
};

/**
 * 대장 값이 없을 때(null)의 문구 — 명세 1.8. 대장을 찾지 못했거나 대장이 그 항목을 주지 않은 것이다.
 * 「해당 없음」 · 0 · 빈칸으로 보이면 확인하지 못한 것이 확인된 것처럼 읽힌다.
 */
export const LEDGER_UNVERIFIABLE_LABEL = '확인 불가';

/**
 * 위반건축물을 확인하지 못했을 때의 문구. 위반건축물은 보증보험 집 단위 조건이라(RISK-05) 확인 불가를
 * 「위반 없음」으로 넘기면 안 되고, 사용자가 직접 대장을 떼어 봐야 한다는 것까지 알린다.
 */
export const VIOLATION_BUILDING_UNVERIFIABLE_LABEL = '확인 불가 — 계약 전 건축물대장 열람 필요';

/** 주거용처럼 해당 여부를 나타내는 대장 값. null은 「확인 불가」다 */
export const applicabilityLabel = (isApplicable: boolean | null) => {
  if (isApplicable === null) return LEDGER_UNVERIFIABLE_LABEL;
  return isApplicable ? APPLICABILITY_LABEL.applicable : APPLICABILITY_LABEL.notApplicable;
};

/** 위반건축물 — null이면 대장 열람 안내까지 붙는다. 참 · 거짓은 applicabilityLabel과 같은 문구다 */
export const violationBuildingLabel = (violationBuilding: boolean | null) =>
  violationBuilding === null ? VIOLATION_BUILDING_UNVERIFIABLE_LABEL : applicabilityLabel(violationBuilding);

/**
 * 값이 있으면 표기 함수로 바꾸고 없으면(null) 「확인 불가」다 — 주용도 · 면적 · 사용승인일 · 수집 시각.
 * 컴포넌트마다 `value ?? …`나 삼항을 적지 않게 여기서 처리한다.
 */
export const ledgerValueLabel = <T>(value: T | null, format: (value: T) => string): string =>
  value === null ? LEDGER_UNVERIFIABLE_LABEL : format(value);

/** 면적 (㎡) 표기 — ledgerValueLabel의 format으로 넘긴다 */
export const areaLabel = (squareMeters: number) => `${squareMeters}㎡`;

/**
 * 건축물대장 수집 경로 — 명세 1.8 dataSource. 대장을 찾지 못하면 null로 온다.
 * 값이 늘면 백엔드 · 명세와 함께 여기에 추가한다.
 */
export const LEDGER_DATA_SOURCES = ['MOCK', 'BUILDING_HUB'] as const;
export type LedgerDataSource = (typeof LEDGER_DATA_SOURCES)[number];

/** 대장을 찾지 못했을 때 대장 섹션의 안내 문구 */
export const LEDGER_MISSING_NOTICE = '건축물대장을 조회하지 못했습니다.';

/**
 * 대장 없음 판정 — 명세 1.8 「대장이 없으면 propertyId만 있고 나머지 전부 null」. 필드 하나가 null인 것은
 * 대장 없음이 아니다(그 항목만 확인 불가). 도메인 모듈은 api를 import하지 않으므로 형태만 받는다.
 */
export const isLedgerMissing = (ledger: object) =>
  Object.entries(ledger).every(([key, value]) => key === 'propertyId' || value === null);

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

// ── 표시 문구 헬퍼 ────────────────────────────────────────────────────────
// domain/risk.ts와 같은 형태다. 서버가 우리가 모르는 코드를 보내도 화면이 빈칸이 되지 않게
// 코드 문자열을 그대로 보여준다 — 지금 매물 유형은 아파트 · 오피스텔 둘뿐이지만 명세 1.1이
// 연립다세대 · 단독다가구를 적재 서비스를 붙일 때 추가한다고 적고 있어, 그때 새 값이 실제로 온다.

function labelOf(labels: Record<string, string>, code: string): string {
  return labels[code] ?? code;
}

// 파라미터를 열거 유니언이 아니라 string으로 받는다 — 모르는 코드를 그대로 돌려주는 것이 이 함수들의 일이고,
// 유니언으로 좁히면 그 경우를 호출하는 쪽에서 단언해야 한다 (domain/risk.ts와 같다)

export const contractTypeLabel = (contractType: string) => labelOf(CONTRACT_TYPE_LABEL, contractType);
export const propertyTypeLabel = (propertyType: string) => labelOf(PROPERTY_TYPE_LABEL, propertyType);
