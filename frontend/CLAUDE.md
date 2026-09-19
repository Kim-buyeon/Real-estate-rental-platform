# frontend

React · TypeScript · Vite · TanStack Query v5 · React Router 8 · axios · CSS Modules + CSS 변수

세부 버전은 기술 스택 정의서 7장이 고정한다. 여기에는 적지 않는다.

## 폴더 구조

```
frontend/
├── index.html                  카카오맵 SDK <script> 한 줄 — kakao-map 스킬 2장
├── vite.config.ts              envDir = 저장소 루트 · server.proxy /api → 백엔드
├── design/                     디자인 정본. 코드가 아니라 코드의 근거다
│   ├── DESIGN.md                 디자인 토큰 정의서. styles/tokens.css 를 만드는 입력
│   └── examples/<아키타입>/       레이아웃 맵. 섹션 순서 · 그리드 · 여백 리듬 · 반응형 붕괴
└── src/
    ├── main.tsx                진입. 세션 복원 → QueryClientProvider → RouterProvider
    ├── app/                    조합 루트. 앱 전체에 하나만 있는 것. main.tsx 외에 아무도 app을 import하지 않는다
    │   ├── router.tsx            라우트 표 (createBrowserRouter) · 인증 필수 화면의 가드
    │   ├── queryClient.ts        QueryClient 기본값
    │   ├── NotificationStream.tsx  SSE 연결 관리자 — EventSource를 여는 유일한 곳
    │   ├── AppShell.tsx          공통 레이아웃. 레이아웃 맵을 따른다
    │   ├── AppFooter.tsx         공통 푸터. 지도 화면은 렌더하지 않는다
    │   └── routeHandle.ts        라우트의 성질(`handle`) 타입과 좁히는 함수
    ├── session/                토큰 보관(store.ts) · 로그인 상태 훅(useSession.ts)
    ├── pages/                  라우트 하나 = 파일 하나. 조합만 한다
    ├── features/<도메인>/       user · property · risk · loan · notification
    │   ├── components/           그 도메인의 데이터 형태를 아는 컴포넌트
    │   ├── hooks/                그 도메인의 화면 로직
    │   ├── map/                  property에만. window.kakao를 읽는 유일한 폴더
    │   └── index.ts              밖으로 내보내는 것. 다른 도메인은 이것만 import
    ├── components/ui/          공용 UI. 디자인 토큰 정의서의 컴포넌트 어휘와 1:1. 도메인을 모른다
    ├── api/
    │   ├── client.ts             axios 인스턴스 하나 + request<T>() — 봉투 · 오류 · 재발급
    │   ├── types.ts              ApiResponse · CursorPage · ApiError
    │   └── <도메인>.ts            엔드포인트 함수 + 요청·응답 타입. API 명세 문서 하나 = 파일 하나
    ├── queries/<도메인>.ts      queryOptions 팩토리 + 뮤테이션 훅. 쿼리 키가 만들어지는 유일한 곳
    ├── domain/<도메인>.ts       열거값 · 표시 문구 · 도메인 상수
    ├── lib/                    순수 함수. format.ts (금액 · 비율 · 일시) · routes.ts (경로 조립)
    ├── styles/
    │   ├── tokens.css            정의서 export 출력. 손으로 고치지 않는다
    │   ├── typography.css        정의서 typography 역할 클래스
    │   └── global.css            리셋 · 폰트 로드 · keep-all · 프로젝트 정의 변수
    └── test/                   setup.ts · msw/handlers/<도메인>.ts — 응답은 API 명세의 예시 그대로
                                jsdom에 없는 전역의 가짜 — eventSource.ts · kakao.ts · resizeObserver.ts
```

테스트 파일은 대상 옆에 `<이름>.test.tsx`로 둔다. 무엇을 테스트하는지는 테스트 전략 문서가 정한다.

도메인 폴더 이름은 기능 ID 접두가 정한다 — `USER` → `user` · `PROP` → `property` · `RISK` → `risk` · `LOAN` → `loan` · `NOTI` → `notification`. 관심 매물(PROP-05)과 지도(PROP-02)는 `property`다. `ADMIN`은 화면이 없다.

### import 방향

```
app ──▶ 아래 전부 (조합 루트)

pages ──▶ features ──▶ queries ──▶ api ──▶ api/client ──▶ session
  │          │            │          │
  │          └────────────┴──────────┴──▶ domain · lib        누구나 쓰는 바닥층
  │          └──▶ components/ui ──▶ styles
  └──▶ session (useSession)
```

- 화살표 반대로 import하지 않는다. `api`가 `queries`를, `components/ui`가 `domain`을, `session`이 `api`를 알지 못한다. `app`은 무엇이든 import하지만 `main.tsx` 외에 아무도 `app`을 import하지 않는다.
- **호출은 계층을 건너뛰지 않는다** — 규약. `pages` · `features`는 `api`의 함수를 직접 부르지 않고 `queries`를 거친다. 타입은 예외다 — `api/<도메인>.ts`의 응답 타입을 `import type`으로 가져와 props에 쓴다.
- `features/<A>`가 `features/<B>`를 쓰려면 `features/<B>/index.ts`가 내보낸 것만 쓴다. 깊은 경로로 들어가지 않는다. `pages`도 마찬가지다.
- 순환이 생기면 공용(`components/ui` · `domain` · `lib`)으로 올린다. 두 도메인이 같은 것을 원하면 그것은 도메인의 것이 아니다.

---

## 재사용 원칙

**같은 것은 한 곳에만 있다. 만들기 전에 그 자리를 읽는다.** 자리가 정해져 있으므로 「있는지 몰랐다」는 성립하지 않는다.

| 만들려는 것 | 먼저 읽는 파일 | 있으면 |
| --- | --- | --- |
| 컴포넌트 | `components/ui/` · 그 도메인의 `features/<도메인>/components/` | 그것을 쓴다. 변형이 필요하면 props를 늘린다 |
| API 호출 | `api/<도메인>.ts` | 같은 경로의 함수를 쓴다. 두 번째 함수를 만들지 않는다 |
| 데이터 훅 | `queries/<도메인>.ts` | 같은 정의를 쓴다. 키를 새로 만들지 않는다 |
| 열거값 · 문구 · 색 매핑 | `domain/<도메인>.ts` | 매핑을 쓴다. 조건 분기로 다시 만들지 않는다 |
| 금액 · 비율 · 일시 표기 | `lib/format.ts` | 그 함수를 쓴다. `toLocaleString`을 컴포넌트에 직접 쓰지 않는다 |

- **컴포넌트 어휘 하나 = 컴포넌트 하나.** 버튼은 `Button` 하나다. 색·크기·아이콘·로딩은 props다. `IconButton` · `SmallButton` · `LinkButton`을 만들지 않는다.
- **엔드포인트 하나 = 함수 하나.** 컴포넌트와 훅은 URL을 모른다.
- **쿼리 키는 팩토리에서만 나온다.** `queryKey: [` 리터럴이 `queries/` 밖에 있으면 위반이다.
- **공용 UI · API 함수 · 쿼리 정의는 기능보다 먼저 만든다.** 작업 흐름 문서의 원칙 「공통 클래스·설정·스키마를 기능보다 먼저 만든다」를 프론트에 적용한 것이다. 이 세 층은 처음부터 한 곳이므로 중복이 생길 자리가 없다. 그 밖의 추상화는 규약대로 **세 번째에** 판단한다.

---

## 타입 · 열거값 (`domain/` · `api/<도메인>.ts`)

- 요청·응답 타입은 **API 명세의 응답 예시에서 그대로 쓴다.** 필드명을 바꾸지 않고 없는 필드를 넣지 않는다. 명세와 백엔드가 다르면 프론트에서 맞추지 않고 알린다.
- 응답 타입 이름은 PascalCase 명사다. `Response` · `DTO` · `Type` 접미를 붙이지 않는다. `PropertyDetail` · `PropertyMarker` · `RiskAnalysis` · `LoanLimit` · `Profile`.
- 여러 엔드포인트가 공유하는 요청 형태는 한 번만 정의한다. 자치구 집계 · 마커 · 목록이 같은 필터를 쓰므로 `PropertyFilter` 하나다 — 매물 API 명세 1.1.
- **열거값은 `enum` 키워드가 아니라 문자열 유니언 + `as const` 객체다.** 값은 명세의 문자열 그대로. 명세에 없는 값을 추가하지 않는다.
- **표시 문구와 색 토큰은 열거값에 붙인 매핑이 갖는다.** `switch` · `if`로 변환하지 않는다 — 규약 「표시 문구는 열거형이 갖는다」. 문구는 규약 도메인 용어 표와 명세의 괄호 표기(전세 · 월세 · 반전세)를 따른다.
- 금액은 원 단위 정수, 비율은 수치, 일시는 ISO 8601 문자열로 받는다 — 공통 규약 1.1. 받은 그대로 두고 표기만 `lib/format.ts`가 바꾼다. 일시 표기 시간대는 `Asia/Seoul`. 날짜 라이브러리를 두지 않는다 — `Intl`로 충분하다.
- `any`를 쓰지 않는다. 모르는 값은 `unknown`으로 받아 좁힌다. `!` 단언은 이유를 주석으로 남길 때만.
- `tsconfig`는 `strict` · `noUncheckedIndexedAccess` · `verbatimModuleSyntax`. 타입만 쓰는 import는 `import type`.

```ts
// domain/risk.ts
export const RISK_GRADES = ['SAFE', 'CAUTION', 'DANGER'] as const;
export type RiskGrade = (typeof RISK_GRADES)[number];

// 문구 · 토큰 이름은 규약 도메인 용어 표를 따른다. 색 값(hex)은 여기 없다 — tokens.css가 갖는다
export const RISK_GRADE_LABEL: Record<RiskGrade, string> = {
  SAFE: '안전', CAUTION: '주의', DANGER: '위험',
};
export const RISK_GRADE_TOKEN: Record<RiskGrade, 'risk-safe' | 'risk-caution' | 'risk-danger'> = {
  SAFE: 'risk-safe', CAUTION: 'risk-caution', DANGER: 'risk-danger',
};

// 미분석 — 분석 이력이 없어 riskGrade가 null인 상태. 열거값이 아니므로 매핑 밖에 둔다 (공통 규약 「값 없음」)
export const UNANALYZED_LABEL = '미분석';
export const UNANALYZED_TOKEN = 'risk-unanalyzed';
export const riskGradeLabel = (grade: RiskGrade | null) => (grade ? RISK_GRADE_LABEL[grade] : UNANALYZED_LABEL);
export const riskGradeToken = (grade: RiskGrade | null) => (grade ? RISK_GRADE_TOKEN[grade] : UNANALYZED_TOKEN);
```

등급 → 배지 변형, 등급 → 마커 색처럼 열거값에서 시각 요소로 가는 매핑도 전부 여기다. `features/`는 매핑을 읽기만 한다. **`null`(미분석) 처리도 여기의 함수가 한다** — 컴포넌트마다 `grade ?? …`를 적지 않는다.

---

## API 클라이언트 (`api/client.ts`)

axios 인스턴스 하나와 `request<T>()` 하나다. **API 함수는 `request<T>()`만 부른다.** `axios.get` · `fetch`를 직접 쓰지 않는다.

| 층 | 하는 일 |
| --- | --- |
| 요청 인터셉터 | 액세스 토큰이 있으면 `Authorization: Bearer`를 붙인다. 인증 「선택」 엔드포인트도 붙인다 — 개인화 결과(`wishlisted`)를 받기 위해서다 |
| `request<T>()` | 봉투를 벗긴다. **204이거나 본문이 비어 있으면 `null`** — 봉투 파싱을 시도하지 않는다. `success: true`면 `data`만 돌려주고, `success: false`면 상태가 200이어도 `ApiError`를 던진다 — 공통 규약은 200 + `success: false`(안내)를 허용한다 |
| 응답 오류 인터셉터 | 어떤 실패든 `ApiError`로 바꿔 던진다. 상태 코드 · `error.code` · `message` · `field`와, 명세가 그 오류에만 주는 필드(재분석 429의 `retryAfter`)를 담는다. 응답이 없는 실패(네트워크 · 타임아웃)는 `code`를 `NETWORK_ERROR`로 둔다 — **프론트에만 있는 유일한 오류 코드이며, 프론트가 문구를 갖는 유일한 오류다** |
| 재발급 | 401 + `AUTH_TOKEN_EXPIRED`면 재발급을 **한 번** 하고 원 요청을 **한 번** 재시도한다. 동시에 난 401들은 진행 중인 재발급 하나를 공유한다. 재발급이 실패하면 세션을 비운다 — 로그인 화면으로의 이동은 `app/router.tsx`의 가드가 세션 없음을 보고 한다. `client`는 라우터를 모른다 |
| 파라미터 직렬화 | 배열 파라미터(`riskGrade` 다중 선택)는 **같은 키 반복**이고 값은 **사전순 정렬** — 공통 규약 1.1. axios 기본값은 `riskGrade[]=SAFE` 꼴이라 그대로 쓰지 않는다. `paramsSerializer: { indexes: null }`로 반복 키를 만들고, 정렬은 요청 인터셉터에서 배열 값에 한 번 적용한다. 이 설정은 `client.ts` 한 곳에만 있다 |

- 재발급 대상에서 빼는 경로: 로그인 · 가입 · 재발급 자신. 로그인 실패(`AUTH_INVALID_CREDENTIAL`)는 재발급이 아니라 그대로 던진다.
- **재발급 함수 `reissue()`는 `client.ts` 안에 있고 인터셉터를 거치지 않는 호출로 한다.** `api/user.ts`에 두면 `client` → `user` → `client` 순환이다. 기동 시 세션 복원(`main.tsx`)도 이 함수를 쓴다. 회원·인증 명세의 재발급 행은 이 함수가 담당한다.
- 오류 문구는 서버 `error.message`를 그대로 보여준다. **코드별 문구를 프론트에 다시 적지 않는다** — 두 곳에 적으면 한쪽만 고쳐진다. 예외는 `NETWORK_ERROR` 하나다.
- 기본 경로는 `/api` 상대 경로다. 개발은 Vite `server.proxy`가 백엔드로 넘긴다. 환경 변수로 두지 않는다 — 운영에서 프론트를 어디서 서빙하는지는 시스템 구성서가 아직 정하지 않았고, 같은 오리진이 아니게 정해지면 그때 변수를 추가하고 `.env.example`에 올린다.
- 토큰을 콘솔 · 로그 · 오류 보고에 남기지 않는다. `ApiError`에 요청 헤더를 넣지 않는다.

```ts
// api/client.ts — 골격
const instance = axios.create({ baseURL: '/api', paramsSerializer: { indexes: null } });   // riskGrade=SAFE&riskGrade=CAUTION

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await instance.request<ApiResponse<T>>(config);
  if (response.status === 204 || !response.data) return null as T;   // axios는 빈 본문을 ''로 준다
  if (!response.data.success) throw ApiError.fromEnvelope(response.data.error, response.status);
  return response.data.data;
}

instance.interceptors.request.use(attachAccessToken);
instance.interceptors.response.use(undefined, async (error: AxiosError<ApiResponse<never>>) => {
  const apiError = ApiError.fromAxios(error);
  if (apiError.status === 401 && apiError.code === 'AUTH_TOKEN_EXPIRED' && canRetry(error.config)) {
    await reissue();                              // 진행 중인 재발급이 있으면 그것을 기다린다
    return instance.request(markRetried(error.config));
  }
  throw apiError;
});
```

---

## API 함수 (`api/<도메인>.ts`)

**API 명세 문서 하나 = 파일 하나.** 회원·인증 명세 → `user.ts`, 매물 → `property.ts`, 위험도 → `risk.ts`, 대출 → `loan.ts`, 알림 → `notification.ts`. 함수를 추가하기 전에 그 파일을 읽는다.

- **명세 표의 행 하나 = 함수 하나.** 같은 경로를 두 함수로 만들지 않는다. 예외는 둘 — 응답 형태가 파라미터로 갈리는 매물 조회는 목록과 지도 마커의 타입이 다르므로 `fetchPropertyList` · `fetchPropertyMarkers` 둘이고 (매물 API 명세 1.3), 실시간 수신 행은 `request<T>()` 함수가 아니라 `EventSource`다 (아래 알림 수신).
- 함수는 파라미터를 받아 `request<T>()`를 부르고 타입을 붙여 돌려준다. 그 이상 하지 않는다 — 캐싱 · 재시도 · 가공은 `queries/`와 컴포넌트의 일이다.
- 경로 · 파라미터명 · 열거값은 명세에 있는 것만 쓴다. 필요한 것이 명세에 없으면 명세를 먼저 고친다.
- **차기 범위 엔드포인트의 함수를 만들지 않는다.** 결번 기능(소셜 로그인 · 검색 이력 · 탈퇴 · 시세 통계 · 전환 계산 · 신청기한 · LOAN-02~06 · 푸시 토큰)이 그것이다. 범위는 서비스 기능 정의서 개요가 정한다.

| 명세의 형태 | 함수 이름 | 예 |
| --- | --- | --- |
| GET | `fetch<자원>` | `fetchProfile()` · `fetchPropertyDetail(propertyId)` · `fetchDistrictCounts(filter)` · `fetchRiskAnalysis(propertyId)` · `fetchLoanLimit(propertyId)` · `fetchNotifications(cursor)` |
| POST 생성 | `add<자원>` | `addWishlist(propertyId)` |
| POST 처리 (동사형 명사 하위 경로 · 인증 · 발급) | 그 동사 | `login(form)` · `signup(form)` · `logout()` · `reanalyzeRisk(propertyId)` — 재발급은 `client.ts`의 `reissue()` |
| PUT | `update<자원>` | `updateProfile(profile)` · `updateNotificationSubscriptions(settings)` |
| PATCH 읽음 | `mark<자원>Read` | `markNotificationRead(notificationId)` · `markAllNotificationsRead()` |
| DELETE | `remove<자원>` | `removeWishlist(propertyId)` |

```ts
// api/property.ts
export interface PropertyFilter { district?: string; contractType?: ContractType; depositMin?: number; /* 명세 1.1 */ }
export interface BoundingBox { minLat: number; maxLat: number; minLng: number; maxLng: number }
export interface PropertyMarker { propertyId: number; latitude: number; longitude: number; deposit: number; riskGrade: RiskGrade | null; debtRatio: number | null; /* 명세 1.4 — 분석 이력이 없으면 null */ }
export interface PropertyMarkerList { items: PropertyMarker[]; count: number }

export const fetchPropertyMarkers = (filter: PropertyFilter, bbox: BoundingBox) =>
  request<PropertyMarkerList>({ url: '/properties', params: { ...filter, ...bbox } });

export const fetchPropertyDetail = (propertyId: number) =>
  request<PropertyDetail>({ url: `/properties/${propertyId}` });

export const addWishlist = (propertyId: number) =>
  request<null>({ method: 'POST', url: '/me/wishlist', data: { propertyId } });
```

---

## 쿼리 (`queries/<도메인>.ts`)

TanStack Query v5의 `queryOptions()`로 **엔드포인트마다 정의를 하나** 만든다. 컴포넌트는 `useQuery(propertyQueries.detail(id))`처럼 정의를 넘기고, 무효화는 같은 정의의 `queryKey`를 쓴다. 키와 함수와 타입이 한 곳에 있으므로 어디서 쓰든 같다.

- **요청을 바꾸는 값은 전부 키에 들어간다.** 필터 · 표시 영역 좌표 · 커서 · 식별자. 키에 없는 값으로 요청하면 다른 조건의 결과가 캐시에서 나온다.
- 키는 배열이고 첫 원소가 도메인 루트다. 루트는 여섯 — `user` · `property` · `wishlist` · `risk` · `loan` · `notification`. 관심 매물은 매물 폴더에 있지만 루트를 따로 둔다 — 무효화 범위가 다르다.
- 지도 마커 · 자치구 집계는 `placeholderData: keepPreviousData` — 지도가 움직이는 동안 비지 않는다 (기술 스택 정의서 4장). `keepPreviousData`는 v5의 함수다. v4의 불리언 옵션을 쓰지 않는다.
- 커서 목록(관심 매물 · 알림 · 매물 목록)은 `infiniteQueryOptions()`로, `getNextPageParam`은 `hasNext ? nextCursor : undefined`. 응답의 `nextCursor`를 그대로 다음 요청에 넣는다 — 공통 규약 1.4.
- 뮤테이션 훅은 같은 파일에 두고 `onSuccess`에서 아래 표대로 무효화한다. 화면 컴포넌트가 `queryClient`를 직접 잡고 무효화하지 않는다. 예외는 `app/NotificationStream.tsx` — 수신 이벤트의 무효화가 그것의 일이다.
- `QueryClient` 기본값(`staleTime` · `retry`)은 `app/queryClient.ts` 한 곳이다. 쿼리별 예외는 그 정의 안에만 적는다.
- 로딩 판별은 `isPending`(첫 로딩) · `isFetching`(재조회)이다. v4의 `isLoading` 의미로 쓰지 않는다.

```ts
// queries/property.ts
export const propertyQueries = {
  all: () => ['property'] as const,
  markers: (filter: PropertyFilter, bbox: BoundingBox) =>
    queryOptions({
      queryKey: [...propertyQueries.all(), 'markers', filter, bbox] as const,
      queryFn: () => fetchPropertyMarkers(filter, bbox),
      placeholderData: keepPreviousData,
    }),
  detail: (propertyId: number) =>
    queryOptions({
      queryKey: [...propertyQueries.all(), 'detail', propertyId] as const,
      queryFn: () => fetchPropertyDetail(propertyId),
    }),
};

export const wishlistQueries = {
  all: () => ['wishlist'] as const,
  list: () =>
    infiniteQueryOptions({
      queryKey: [...wishlistQueries.all(), 'list'] as const,
      queryFn: ({ pageParam }) => fetchWishlist(pageParam),
      initialPageParam: undefined as string | undefined,     // v5는 필수
      getNextPageParam: (lastPage) => (lastPage.hasNext ? lastPage.nextCursor : undefined),
    }),
};

export function useAddWishlist() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: addWishlist,
    onSuccess: (_, propertyId) => {
      queryClient.invalidateQueries({ queryKey: wishlistQueries.all() });
      queryClient.invalidateQueries({ queryKey: propertyQueries.detail(propertyId).queryKey });
    },
  });
}
```

### 무효화 연쇄

| 계기 | 무효화 |
| --- | --- |
| 관심 매물 등록 · 해제 성공 | `wishlist` 전체 · `property.detail(id)` — `wishlisted`가 바뀐다 |
| 재분석 성공 | `risk.analysis(id)` · `property.detail(id)`. 응답의 `gradeChanged`가 참이면 `property` 전체와 `wishlist` 전체도 — 마커 색과 `previousGrade`가 바뀐다 |
| 프로필 수정 성공 | `user.profile` · `loan` 전체 — 자격 정보가 한도 계산의 입력이다 |
| 알림 읽음 처리 성공 | `notification.list` |
| 알림 구독 설정 수정 성공 | `notification.subscriptions` |
| SSE 이벤트 수신 (모든 유형) | `notification.list` — 본문은 목록 조회로 가져온다 (알림 전달 문서 1.2) |
| SSE 위험도 변경 (`RISK_CHANGE`, `propertyId` 포함) | **모든 유형 행에 더해** `risk.analysis(propertyId)` · `property` 전체 · `wishlist` 전체 — 등급이 바뀐 경우에만 오는 알림이므로 재분석 성공의 `gradeChanged` 경우와 같다. 마커 색 · 자치구 `gradeCounts` · `previousGrade`가 바뀐다 |
| SSE 등기 변동 (`REGISTRY_CHANGE`, `propertyId` 포함) | **모든 유형 행에 더해** `risk.registry(propertyId)` · `risk.analysis(propertyId)` · `property.detail(propertyId)`. 위 `RISK_CHANGE` 행에 누적하지 않는다 — 두 유형은 한 이벤트에 함께 오지 않고, 누적이라면 `risk.analysis`를 다시 적을 이유가 없다 |
| SSE 재연결 성공 | `notification.list` — 끊긴 사이의 알림 (알림 전달 문서 1.2) |
| 로그인 · 로그아웃 | `queryClient.clear()` — 개인화 결과가 다음 사용자에게 남지 않는다 |

---

## 상태

| 종류 | 두는 곳 | 규칙 |
| --- | --- | --- |
| 서버 상태 | TanStack Query만 | 응답을 `useState`에 복사하지 않는다. 화면이 보는 출처는 캐시 하나다 |
| 세션 | `session/` | 아래 |
| 화면 상태 (필터 · 지도 단계 · 패널 열림) | 그것을 소유하는 페이지 · 컴포넌트의 `useState` / `useReducer` | 화면 사이에 공유해야 하면 컨텍스트. **전역 상태 라이브러리를 두지 않는다** — 기술 스택 정의서 4장 |
| **고른 매물** | `useSearchParams` — `/map?propertyId=`. 조립과 파싱은 `lib/routes.ts` 한 곳 | 링크로 가리킬 수 있어야 하는 것만 URL에 둔다. 필터 · 단계 · 탭을 올리면 지도를 움직일 때마다 히스토리가 쌓인다 (이슈 #104 계획 승인) |
| 폼 입력 | 폼 컴포넌트의 상태 | 폼 라이브러리를 두지 않는다. 폼은 로그인 · 가입 · 자격 정보 · 알림 구독 넷이다 |

- **필터는 지도 단계 · 선택 매물과 별도 상태다.** 지도 페이지가 소유하고, 단계가 바뀌거나 패널이 열려도 건드리지 않는다. 자치구로 되돌아갈 때 필터가 초기화되는 것이 이슈 가이드가 예로 든 버그다.
- **화면에서 판정하지 않는다.** 위험 등급 · 전세가율 · 대출 한도 · 보증한도는 서버 값을 표시만 한다.

### 세션 (`session/`)

| 토큰 | 보관 | 이유 |
| --- | --- | --- |
| 액세스 토큰 | 모듈 변수 (메모리) | 저장소에 두지 않아 노출면을 줄인다. 만료는 401로 안다 — 타이머로 선제 갱신하지 않는다. 경로가 둘이면 경합한다 |
| 리프레시 토큰 | `localStorage` | 명세가 본문으로 주고받으므로 쿠키가 아니다. 새로고침 뒤에도 로그인이 유지되어야 한다 |

- 기동 시 리프레시 토큰이 있으면 `main.tsx`가 **재발급을 먼저 한 번 하고** 렌더링한다. 인증 「선택」 엔드포인트가 처음부터 개인화 결과를 받게 하기 위해서다. 실패하면 세션을 비운다.
- 로그아웃은 `logout()` 호출 → 세션 비움 → `queryClient.clear()` 순서다. SSE 연결은 로그인 상태가 풀리면서 `NotificationStream`이 내려가 닫힌다.
- `session/store.ts`는 React를 모르는 모듈이다 — `api/client`가 토큰을 읽고 쓴다. 컴포넌트는 `useSession()`(`useSyncExternalStore`)으로 로그인 상태만 읽는다. 토큰 값 자체를 컴포넌트에 넘기지 않는다.
- 재발급 호출은 `session`이 아니라 `api/client`가 한다. `session`이 `api`를 import하면 방향이 뒤집힌다.
- 인증 필수 화면은 `app/router.tsx`에서 막는다. 세션이 없으면 `/login`으로 보내고, 로그인 뒤 원래 화면으로 돌아간다.

---

## 알림 수신 (`app/NotificationStream.tsx`)

`EventSource`를 여는 곳은 여기 하나다. 로그인 상태에서만 마운트되고 로그아웃 · 언마운트에서 닫는다. 규칙은 알림 전달 문서 1.2가 정하고, 여기는 그것을 코드 자리로 옮긴 것이다.

- **연결마다 일회용 티켓을 발급받아 그것으로 연다** — `POST /api/notifications/stream-ticket`으로 짧은 수명의 티켓을 받아 `new EventSource('/api/notifications/stream?ticket=…')`. `EventSource`는 헤더를 붙일 수 없어 연결 자격을 URL로 넘겨야 하는데, **액세스 토큰을 URL에 담지 않는다** — RFC 9700이 MUST NOT으로 규정하며 프록시 · 서버 접근 로그 · 브라우저 이력에 30분짜리 토큰이 남는다. 발급 호출은 Bearer 헤더를 붙일 수 있는 보통의 POST다 — 알림 API 명세 1.1.
- **티켓은 받아서 URL에 쓰고 버린다.** 모듈 변수 · 저장소 · 쿼리 캐시 어디에도 두지 않는다. 티켓은 1회용이라 재사용할 수 없고, 남겨 둘 이유가 없는 값을 남기면 URL에 담지 않기로 한 의미가 없다.
- 발급은 `queries/`를 거치지 않고 `api/notification.ts`의 함수를 직접 부른다 — 캐시에 남아서는 안 되는 1회용 값이라 쿼리가 아니고, 여기는 무엇이든 import하는 `app/` 조합 루트다. `queryClient`를 직접 잡는 것과 같은 예외다.
- **`EventSource`는 연결 시점에 `globalThis.EventSource`로 읽는다.** 모듈 최상위에서 참조를 잡아 두지 않는다. jsdom에는 없어 테스트가 전역에 주입하는데(테스트 전략 문서 1.2), 미리 잡아 두면 주입한 것이 쓰이지 않는다.
- 이벤트 이름은 알림 유형과 같다 — 알림 API 명세 1.4. 유형마다 `addEventListener(type)`를 건다. 목록은 `domain/notification.ts`의 유형 상수다. 이름 없는 `onmessage`에 기대지 않는다.
- **수신하면 무효화만 한다** — 위 표. 이벤트 본문을 화면 상태에 넣지 않는다. 토스트는 유형의 문구만 보여준다. 본문은 목록 조회가 가져온다.
- **브라우저의 자동 재연결에 기대지 않는다.** 브라우저는 끊긴 URL을 그대로 다시 보내는데 티켓은 1회용이라 이미 소비되어 거부된다. `error`에서 `readyState`가 `CLOSED`면 백오프 뒤 **새 티켓을 발급받아** 새 연결을 만든다 — 재연결에서도 티켓을 재사용하지 않는다. `open`마다 `notification.list`를 무효화한다 — 끊긴 사이의 알림.
- **발급 실패도 같은 백오프로 다시 시도한다.** 재시도는 항상 타이머를 거쳐 1초에서 30초로 벌어진다 — 즉시 다시 부르는 경로를 두지 않는다. **멈추는 것은 401 · 403뿐이고**(다시 시도해도 결과가 같다) **그 밖의 실패는 30초 간격으로 계속 시도한다** — 서버가 복구되면 붙어야 하기 때문이다. 다시 로그인하면 이 컴포넌트가 새로 마운트되며 연결한다. 실시간 전달 실패는 기능 실패가 아니므로 화면에 오류로 띄우지 않는다.
- **언마운트 · 로그아웃 뒤에 도착한 발급 응답은 연결을 만들지 않는다.** 발급을 기다리는 동안 정리가 끝났으면 받은 티켓을 버린다. 정리 경로가 이미 세운 「멈춤」 표시를 발급 뒤에 한 번 더 본다.
- 연결 URL을 만드는 함수는 하나다. **티켓 외의 것을 URL에 넣지 않는다.**

---

## 컴포넌트

### 공용 UI (`components/ui/`)

**디자인 토큰 정의서의 `components` 항목 하나 = 컴포넌트 하나.** 정의서에 없는 컴포넌트를 만들지 않는다 — 필요하면 정의서에 먼저 넣고 승인 뒤 만든다 (`frontend-dev`). 정의서에 **키가 없는 것**도 같다 — `dialog` · `toast` · `checkbox` · 로딩 표시는 참고 사이트에서 관측되지 않아 키가 없다(정의서 K-18~K-21). 이들을 쓰는 화면 작업은 정의서 추가가 선행된다.

- 정의서의 변형(`button-primary` · `button-ghost`)은 `variant` prop 값이고, 상태(`-hover` · `-disabled`)는 CSS 상태다. 변형마다 컴포넌트를 만들지 않는다.
- **도메인을 모른다.** `riskGrade` · `contractType`을 받지 않는다. `Badge`는 정의서 `badge-*` 항목 이름을 `variant`로 받는다. 등급 → 변형 매핑은 `domain/risk.ts`에 있고, `features/risk`의 `RiskGradeBadge`가 그것을 읽어 `Badge`에 넘긴다.
- 데이터를 부르지 않는다. 훅은 UI 동작(열림 · 포커스)만.
- 네이티브 요소의 props를 그대로 통과시킨다 (`ComponentPropsWithoutRef<'button'>`). `onClick` · `disabled` · `aria-*`를 다시 정의하지 않는다.
- 스타일은 옆의 `<이름>.module.css` 하나. 변형은 클래스 맵으로 고른다.
- **이동은 라우터의 `Link`, 처리는 `Button`이다.** 버튼처럼 보이는 링크가 필요하면 `Button`이 함께 내보내는 `buttonClassName(variant, size)`를 `Link`에 입힌다. 두 번째 버튼 컴포넌트를 만들지 않는다.

```tsx
// components/ui/Button.tsx
type Variant = 'primary' | 'secondary' | 'ghost';   // 정의서 button 변형 이름과 같게
type Size = 'sm' | 'md';

interface ButtonProps extends ComponentPropsWithoutRef<'button'> {
  variant?: Variant;
  size?: Size;
  isLoading?: boolean;
}

export const buttonClassName = (variant: Variant, size: Size) =>
  [styles.button, styles[variant], styles[size]].join(' ');

export function Button({ variant = 'primary', size = 'md', isLoading = false, type = 'button', disabled, children, ...rest }: ButtonProps) {
  return (
    <button type={type} className={buttonClassName(variant, size)} disabled={disabled || isLoading} aria-busy={isLoading} {...rest}>
      {children}
    </button>
  );
}
```

```css
/* components/ui/Button.module.css — 값은 전부 토큰 변수. 어느 토큰인지는 정의서 button-* 항목이 정한다 */
.button  { border-radius: var(--rounded-pill); padding: var(--spacing-xs) var(--spacing-lg); }
.primary { background: var(--color-primary); color: var(--color-on-primary); }
```

### 기능 컴포넌트 (`features/<도메인>/components/`)

두 종류다. **데이터를 부르는 컴포넌트**는 쿼리 정의를 `useQuery`에 넘기고 표현 컴포넌트를 조합한다. **표현 컴포넌트**는 props만 받는다 — 훅을 부르지 않는다. 둘을 한 파일에 섞지 않는다.

- 공용 UI를 조합해 만든다. 공용 UI가 있는 어휘를 `div`로 다시 그리지 않는다.
- 목록 항목 · 마커 카드처럼 반복 렌더되는 표현 컴포넌트는 `memo`로 감싸고, 부모는 그 props로 렌더마다 새 객체 · 함수를 내리지 않는다.
- 로딩 · 오류 · 빈 상태를 컴포넌트마다 다르게 그리지 않는다. 조회 실패와 빈 상태는 `Alert`, 뮤테이션 실패는 `Toast`. 로딩 표시(스피너 · 스켈레톤)는 정의서 어휘에 없어 프로젝트 정의로 추가된 뒤 공용 UI에 둔다 (아래 표).
- 오류 문구는 `ApiError.message` 그대로다.

### 페이지 (`pages/`)

- 라우트 하나 = 파일 하나. 라우트 파라미터를 읽고 화면 상태를 소유하고 기능 컴포넌트를 조합한다. API 함수 · 쿼리 정의를 직접 부르지 않는다.
- 첫 화면(메인)만 정적 import다. 나머지 페이지는 `lazy`로 나눈다. 페이지 파일만 기본 export를 쓴다 — `lazy` 때문이다. 그 밖은 전부 이름 export.
- 상세는 페이지가 아니라 지도 옆 패널이다 — 매물 API 명세 1.4. 지도 위치 · 확대 수준 · 필터를 유지한다. **고른 매물만 `?propertyId=`로 URL에 둔다** — 관심 매물 · 알림 · 메인에서 그 매물로 보내야 하기 때문이다. 필터 · 단계 · 탭은 로컬 상태다.

| 경로 | 화면 | 기능 | 인증 |
| --- | --- | --- | --- |
| `/` | 메인 | 서비스 소개 · 위험 등급 3단계 안내 · 최근 등록 매물 — 기능 ID 없음. 정의는 「매물 기능 정의서」의 메인 화면 절 | 공개 |
| `/map` · `/map?propertyId=` | 지도 탐색 + 상세 패널 | PROP-08 · 02 · 03 · 04 · RISK-01 · 07 표시 · RISK-08 재분석 · LOAN-01 표시 | 선택 |
| `/login` · `/signup` | 로그인 · 가입 | USER-02 · 01 | 공개 |
| `/me/profile` | 계정 · 자격 정보 | USER-03 | 필수 |
| `/me/wishlist` | 관심 매물 | PROP-05 | 필수 |
| `/notifications` | 알림 목록 | NOTI-05 | 필수 |
| `/me/notification-subscriptions` | 알림 구독 설정 | NOTI-01 | 필수 |

매물 목록(PROP-01)은 별도 화면을 두지 않고 `/map`의 상세 패널 자리에 탭으로 둔다 (PROP-02 계획 승인). 필터와 지도 상태를 화면 간에 다시 맞추지 않기 위함이다.

**경로 문자열의 자리.** 라우트 표의 정본은 `app/router.tsx`다. 링크 · 이동은 경로를 **리터럴로** 쓴다(`'/login'` · `'/me/wishlist'`). `lib/routes.ts`에는 **검색 파라미터를 싣는 경로만** 둔다 — 조립하는 쪽과 읽는 쪽이 여럿이라 파라미터 이름이 갈리면 조용히 깨지기 때문이다. 지금은 `/map?propertyId=` 하나이고, `MAP_PATH`는 그 조립에 쓰려고 있는 상수다. 파라미터 없는 경로를 전부 상수로 올리지 않는다 — 한 경로만 올리면 규칙이 둘이 되고, 전부 올리면 라우트 표와 상수 파일이 같은 목록을 두 번 갖는다 (이슈 #130).

---

## 스타일

CSS Modules와 CSS 변수만 쓴다. CSS 프레임워크 · CSS-in-JS를 두지 않는다.

| 파일 | 내용 | 만드는 방법 |
| --- | --- | --- |
| `styles/tokens.css` | `:root`의 CSS 변수. `--color-<키>` · `--spacing-<키>` · `--rounded-<키>` | **`npx @google/design.md export <정의서> --format css-vars` 출력 그대로.** 손으로 고치지 않는다. 정의서와 같은 커밋 |
| `styles/typography.css` | 정의서 `typography` 역할마다 클래스 하나 — `.type-body` · `.type-heading-1` | `css-vars` export가 타이포그래피를 내지 않아 정의서 값을 손으로 옮긴다. 정의서와 같은 커밋 |
| `styles/global.css` | 리셋 · 폰트 로드 · `word-break: keep-all` · 브레이크포인트 주석 · **정의서가 정했으나 `css-vars` export가 내지 않는 값**(`--container-max` · `--touch-target-min`) | 한 번 |
| `<컴포넌트>.module.css` | 그 컴포넌트의 스타일 | 컴포넌트 옆 |

- **색 · 간격 · 반경은 토큰 변수로만, 글꼴 · 글자 크기 · 행간은 `typography.css`의 역할 클래스로만 쓴다.** hex · `rgb()` · 색 이름 · 간격 px 리터럴 · `font-size`를 컴포넌트 CSS에 적지 않는다. 검사는 `grep -rnE '#[0-9a-fA-F]{3,8}\b' src` — `tokens.css` 외에 결과가 있으면 위반이다 (`quality-check`). **CSS 주석에 이슈 번호를 `#112` 꼴로 적지 않는다** — 이 검사에 그대로 걸린다. 「이슈 112」로 적는다. `.tsx`는 검사 대상이 아니다.
- **px 리터럴이 허용되는 곳은 다섯이고 그 밖은 위반이다** — `typography.css`(정의서 typography 값의 이관), 정의서에 없는 레이아웃 1회성 값(그리드 트랙 · 격자에 맞지 않는 레이아웃 맵 계측값), 1px 보더 두께, 미디어 쿼리 조건, 그리고 **정의서가 정했으나 `css-vars` export가 내지 않는 값**. 미디어 쿼리는 CSS 변수를 쓸 수 없으므로 브레이크포인트 값은 정의서 Responsive Behavior 절의 것을 `global.css` 상단 주석에 한 번 적고 그 값만 쓴다. 다섯째는 컨테이너 최대 폭 · 터치 타겟 하한 · **컴포넌트가 지정한 치수**(높이 · 폭 · 간격 — `button-primary` 60 · `input` 48 · `badge` 30 · `card-form` 폭 540 · `kv-row` 라벨 열 150 · `tabs-underline` 간격 36 같은 것)다. export가 색 · 간격 · 반경만 내므로 쓸 토큰이 없는데, 그렇다고 값을 쓰는 자리마다 px로 다시 적으면 정의서가 값을 고쳐도 따라오지 않는다. **한 번만 적고 그것을 참조한다** — 여러 파일에 걸리는 값은 `global.css`의 프로젝트 정의 변수(`--container-max` · `--touch-target-min`)로, 한 컴포넌트에만 걸리는 값은 그 모듈 CSS의 지역 변수로 모은다. 어느 쪽이든 **어느 정의서 항목에서 온 값인지 주석에 적는다.** 미디어 쿼리 조건과 같은 처리이고 이유도 같다. `frontend-dev`와 `quality-check`의 px 규칙은 이 목록을 가리킨다.
- `tokens.css`에 손으로 쓴 값을 남기지 않는다. 값을 고칠 일이 생기면 정의서를 고치고 export를 다시 돌린다 — 파일을 직접 고치면 다음 export가 그것을 지운다.
- 인라인 `style`은 런타임 계산값(오버레이 좌표 · 진행률)만. 색 · 간격을 넣지 않는다.
- 정의서에 없는 색 · 컴포넌트가 필요하면 만들지 않고 멈춘다. 토큰 추가는 정의서의 작업이다 (`frontend-dev`).
- 섹션 순서 · 그리드 · 여백 리듬은 레이아웃 맵을 따른다. 정의서는 토큰과 컴포넌트, 레이아웃 맵은 합성 — 둘의 분업은 `designmd-spec`.
- 한국어 본문은 `word-break: keep-all`. `global.css`에서 한 번 건다.

---

## 지도 (`features/property/map/`)

SDK를 어떻게 부르고 무엇을 그리는지는 `kakao-map` 스킬이 정한다. 여기는 **자리**다.

- **원 SDK를 직접 쓴다.** 래퍼 라이브러리를 두지 않고, 쓰는 표면만 `map/kakao.d.ts`에 자체 선언한다 — 배포가 끊긴 커뮤니티 타입 패키지에 의존하지 않기 위함이다 (PROP-02 계획 승인).
- 오버레이 내용은 React 컴포넌트를 `createPortal`로 컨테이너 엘리먼트에 그리고, `CustomOverlay`의 생성·제거만 이 폴더가 맡는다. DOM 리스너는 React가 붙이므로 오버레이를 지울 때 엘리먼트와 함께 사라진다.

- `window.kakao`를 읽는 코드는 이 폴더 밖에 없다. 컴포넌트는 이 폴더가 내보낸 함수(지도 생성 · 경계 → 좌표 · 오버레이 생성 · 리스너 등록 해제)를 쓴다.
- **마커가 겹치면 격자로 묶는다.** 표시 영역을 나눈 셀마다 건수 하나를 그리고, 셀에 한 건이면 개별 마커다. 누르면 그 셀로 확대한다 — 같은 건물의 매물은 좌표가 같아 묶지 않으면 위의 하나만 고를 수 있다. 묶는 규칙은 `features/property/map/cluster.ts`, 격자 칸 수·임계값은 아래 「지도 상수」와 같은 파일이다 (`kakao-map` 7장 (가) 결정)
- 마커 오버레이는 `propertyId`로 diff한다 — 새로 온 것만 만들고 사라진 것만 `setMap(null)`, 남은 것은 위치만 옮긴다. 재조회마다 전부 지우고 다시 그리지 않는다. 내용은 포털이 갱신하므로 `setContent`를 쓰지 않는다.
- 마커 배열 · 자치구 집계에서 파생하는 값(정렬 · 그룹핑 · 좌표 계산)은 `useMemo`다. 컴포넌트 본문에서 매 렌더 계산하지 않는다.
- SDK는 `index.html`의 `<script>`로 로드한다. 번들에 넣지 않는다 (`kakao-map` 2장).

### 지도 상수

값은 `features/property/map/constants.ts` 한 곳이 갖는다. 이 표는 어디서 정해지는지와 확정 여부만 기록한다 — 값을 두 곳에 적지 않는다.

| 항목 | 정하는 곳 | 확정 |
| --- | --- | --- |
| 서울 전체가 보이는 `level` (데스크톱 · 모바일) | 상수로 두지 않는다 — `setBounds(서울 경계)` 뒤의 `getLevel()`을 `setMaxLevel`에 넣는다 | 해소 |
| 서울 경계 (남서 · 북동) | `kakao-map` 6장 실측 | **잠정값이 상수 파일에 있다.** 실측으로 대체한다 |
| 자치구 25개 중심 좌표 | `Geocoder.addressSearch('서울특별시 <구>')` 런타임 조회 + 모듈 캐시 (`kakao-map` 3장 ①) | 해소 — 상수 파일에 좌표를 두지 않는다 |
| 자치구 25개 경계 (남서 · 북동) | 얻을 수단이 없다(`Geocoder`는 점만 돌려준다). 중심 좌표 + 자치구 단계 `level`로 이동한다 | **`level` 잠정값이 상수 파일에 있다.** 6장 실측으로 대체한다 |

---

## 환경 변수

- **`.env`는 저장소 루트 하나다.** `vite.config.ts`의 `envDir`를 루트로 둔다. `frontend/.env`를 만들지 않는다. 변수 목록은 `.env.example`이 갖는다.
- `VITE_` 접두 변수는 빌드 시 번들에 치환된다. **비밀을 넣지 않는다.** 카카오 JavaScript 키는 비밀이 아니라 도메인 등록으로 보호되는 값이라 예외다 (`kakao-map` 1장). REST API 키는 서버 것이며 프론트에서 쓰지 않는다.
- 지금 환경 변수를 읽는 곳은 `index.html`의 `%VITE_KAKAO_MAP_KEY%`(Vite HTML 치환) 하나다. 두 번째 변수부터는 `src/env.ts` 하나에서 읽는다 — 컴포넌트가 `import.meta.env`를 직접 읽지 않는다.

---

## 성능 · 보안

| 규칙 | 이유 |
| --- | --- |
| `memo`된 자식 · 지도 · 목록에 렌더마다 새 객체 · 화살표 함수를 props로 내리지 않는다. `useCallback` · `useMemo` | 무거운 자식이 매번 다시 그려진다 |
| 배열 정렬 · 그룹핑 · 거리 계산은 `useMemo` | 마커 수만큼 매 렌더 반복된다 |
| 초기 청크는 Vite 경고 기준 500 kB(비압축)를 넘기지 않는다. 넘으면 라우트 · 라이브러리 단위로 나눈다 | 어느 문서도 상한을 정하지 않아 여기서 정한다 |
| 큰 라이브러리를 최상위에서 통째로 import하지 않는다. 차기 범위의 차트는 그 화면에서 `lazy` | 첫 화면(메인)이 무거워진다 |
| `dangerouslySetInnerHTML`을 쓰지 않는다. 오버레이 `content`는 문자열이 아니라 엘리먼트 | 서버 값(`district`)이 HTML로 해석된다 (`kakao-map` 5장) |
| 등록한 리스너는 정리 함수에서 해제한다 — 지도 이벤트 · 오버레이 DOM 리스너 · `EventSource` | 화면을 오갈 때 누적된다 |
| 클라이언트 검증은 보조다. 정본은 서버 `error.field`이며 폼은 그 필드에 표시한다 | 보안 경계는 서버다 |
| 토큰을 콘솔 · 로그 · 오류 보고에 남기지 않는다. **URL에도 넣지 않는다 — 예외는 없다.** SSE 연결이 URL에 싣는 것은 액세스 토큰이 아니라 짧은 수명의 일회용 티켓이다 (`app/NotificationStream.tsx` 절) | 프록시 접근 로그 · 브라우저 이력 · 리퍼러에 남는다. 액세스 토큰을 URL에 담는 것은 RFC 9700이 MUST NOT으로 규정한다 |
| `.env`를 커밋하지 않는다. 키 값을 코드에 적지 않는다 | `.gitignore` · `.env.example` |

---

## 네이밍

| 대상 | 규칙 | 예 |
| --- | --- | --- |
| 컴포넌트 · 파일 | PascalCase. 파일 하나에 컴포넌트 하나. 파일명 = 컴포넌트명 | `RiskGradeBadge.tsx` |
| 훅 | `use` + PascalCase | `useMapStage` · `useSession` |
| 쿼리 팩토리 | `<쿼리 루트>Queries` | `propertyQueries` · `wishlistQueries` |
| 뮤테이션 훅 | `use` + 동사 + 자원 | `useAddWishlist` · `useReanalyzeRisk` |
| API 함수 | 위 API 함수 표 | `fetchPropertyDetail` |
| 타입 | PascalCase 명사. 접미 없음 | `PropertyDetail` · `PropertyFilter` |
| 열거 유니언 · 값 | 유니언은 PascalCase, 값은 명세의 대문자 스네이크 | `RiskGrade` · `'DEPOSIT_ONLY'` |
| 상수 · 매핑 | 대문자 스네이크 | `RISK_GRADE_LABEL` |
| 불리언 props · 변수 | `is` · `has` · `can` 접두 | `isOpen` · `hasSeniorDebt` |
| 응답 타입의 불리언 필드 | **명세의 이름 그대로.** 접두를 붙이지 않는다 | `violationBuilding` — 외부 공식 문서의 항목명을 옮긴 것이다. 규약 「이름이 의도를 말하게」의 예외 |
| 이벤트 props | `on` + 동작 | `onSelect` · `onStageChange` |
| CSS Module 파일 · 클래스 | 컴포넌트와 같은 이름 · camelCase | `Button.module.css` · `styles.primary` |
| 그 외 파일 (훅 · 쿼리 · api · domain · lib) | camelCase | `useMapStage.ts` · `format.ts` |
| 폴더 | 소문자 | `features/property/map` |

축약하지 않는다. 같은 개념에 같은 이름을 쓴다 — 규약 도메인 용어 표 (`riskGrade` · `debtRatio` · `seniorDebt` · `provider` · `district`).

---

## 미확정

값을 채우지 않고 남긴다. 정해지는 곳과 때를 적는다.

| 항목 | 정하는 곳 | 때 |
| --- | --- | --- |
| 서울 경계 · 자치구 단계 `level` — 상수 파일의 잠정값 | 위 지도 상수 표 — 브라우저 실측 (`kakao-map` 6장) | 실측할 수 있을 때 |
| `setBounds` 뒤 `idle`이 반드시 오는가 (`kakao-map` 6장) | 브라우저 확인 | 같이. 지금은 이동 직후 `getBounds()`를 한 번 더 읽어 둔다 |
| 운영에서 프론트를 서빙하는 위치 · 오리진 | 시스템 구성서 | 다른 오리진이면 API 기본 경로를 환경 변수로 |
| 로딩 표시 컴포넌트(스피너 · 스켈레톤) — 정의서 어휘에 없고 참고 사이트에서도 관측되지 않았다 | 디자인 토큰 정의서 프로젝트 정의 (`designmd-spec` 1.2) | 정의서 K-21이 값을 정할 때. 그 전에는 만들지 않는다 |
| `QueryClient` 기본값(`staleTime` · `retry`) · axios 타임아웃 | 기반 셋업 계획 승인 | 셋업 |
| 린터 · 포매터 | 기술 스택 정의서 5장 | 셋업 |