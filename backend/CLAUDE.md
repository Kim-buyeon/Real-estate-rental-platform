# backend

Spring Boot 4.1 · Java 21 · JPA + MyBatis · PostgreSQL 17

## 패키지 구조

```
com.duri.rentalplatform
├── domain/<도메인>/          user · property · risk · loan · notification · admin
│   ├── controller/
│   ├── service/                <도메인>QueryService · <도메인>CommandService
│   ├── repository/             JPA
│   ├── mapper/                 MyBatis 인터페이스
│   ├── entity/
│   ├── enums/                  해당 도메인 전용 열거형
│   └── dto/
│       ├── request/
│       ├── response/
│       └── condition/
├── common/                    ApiResponse · CursorPage · ErrorCode · BusinessException · BaseEntity
├── config/
└── external/<연동 대상>/       인터페이스 + Mock · Real · Fault 구현
```

MyBatis XML은 `resources/mapper/<도메인>/`에, Flyway 마이그레이션은 `resources/db/migration/`에 둔다.

---

## Entity

- JPA 전용이다. 컨트롤러나 응답에 노출하지 않는다.
- **모든 엔티티는 `BaseEntity`를 상속한다.** 생성일시·수정일시를 개별 엔티티에 선언하지 않는다.
- `@Data`, `@Setter`를 붙이지 않는다. 변경은 의도가 드러나는 메서드로 표현한다.
- 기본 생성자는 `@NoArgsConstructor(access = PROTECTED)`.
- 생성은 정적 팩토리 메서드로 한다. 생성자를 공개하지 않는다.
- 열거형 필드는 `@Enumerated(EnumType.STRING)`.
- 금액·이율은 `BigDecimal`.
- 연관 관계는 지연 로딩을 기본으로 한다.

### BaseEntity

```java
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

- `@EnableJpaAuditing`을 활성화한다.
- 생성일시만 필요한 테이블도 `BaseEntity`를 상속한다. 컬럼 유무는 마이그레이션이 정한다.
- 시각을 코드에서 직접 넣지 않는다. 감사 기능이 채운다.

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wishlist extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long wishlistId;

    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    private Long propertyId;

    public static Wishlist of(User user, Long propertyId) { ... }
}
```

---

## Enum

- 도메인 고유 열거형은 해당 도메인의 `enums` 패키지에 둔다. 여러 도메인이 공유하는 것만 `common`에 둔다.
- 데이터베이스에는 VARCHAR로 저장하고 `@Enumerated(EnumType.STRING)`으로 매핑한다. 저장 값은 상수명과 동일하게 유지한다.
- 상수명은 대문자 스네이크. API 명세서에 정의된 값과 일치시킨다.
- 표시 문구가 필요하면 enum이 필드로 갖는다. 화면에서 조건 분기로 변환하지 않는다.
- 판정 결과처럼 분기 누락이 치명적인 경우 봉인 인터페이스와 함께 사용해 컴파일 시점에 확인한다.

```java
public enum ContractType {
    DEPOSIT_ONLY("전세"),
    MONTHLY_RENT("월세"),
    SEMI_DEPOSIT("반전세");

    private final String label;
}
```

---

## DTO

`record`를 기본으로 작성한다. 용도에 따라 세 종류로 나누며, **하나의 DTO를 요청과 응답에 함께 사용하지 않는다.**

라이브러리 제약으로 기본 생성자나 setter가 필요한 경우에만 클래스로 작성하고, 그 사유를 주석으로 남긴다. 편의를 이유로 클래스를 선택하지 않는다.

| 종류 | 위치 | 역할 |
| --- | --- | --- |
| Request | `dto/request` | 클라이언트 요청 수신. 검증 애노테이션을 붙인다 |
| Response | `dto/response` | 클라이언트 응답. API 명세서의 필드명과 일치시킨다 |
| Condition | `dto/condition` | 서비스에서 매퍼로 넘기는 조회 조건. 요청 값과 조회 조건이 다를 때만 만든다 |

### 규칙

- **Request와 Response를 겸용하지 않는다.** 필드 구성이 같아 보여도 나눈다. 요청에는 서버가 채우는 값(식별자, 생성일시, 판정 결과)이 없고, 응답에는 검증 애노테이션이 필요 없다. 겸용하면 한쪽이 바뀔 때 다른 쪽이 끌려간다.
- **Condition은 요청 값과 조회 조건이 다를 때만 만든다.** 커서 문자열을 식별자로 디코딩하거나, 반경을 바운딩 박스 좌표로 변환하는 것처럼 서비스에서 가공이 일어나는 경우가 해당한다. 필드가 그대로 전달되는 조회는 Request를 매퍼에 넘긴다.
- 변환은 서비스에서 수행한다. 컨트롤러나 매퍼에서 하지 않는다.
- Response 필드명은 API 명세서와 정확히 일치시킨다. 임의로 이름을 바꾸지 않는다.
- Entity → Response 변환은 **정적 팩토리 메서드**로 한다. 별도 변환 클래스나 라이브러리를 두지 않는다.
- MyBatis 매퍼는 Response 또는 그에 준하는 조회 DTO를 직접 반환한다. 변환 단계를 두지 않는다.
- 한 Response를 여러 화면에서 재사용하지 않는다. 용도별로 만든다. (`PropertyResponse` / `PropertyMarkerResponse`)
- 중첩 구조가 필요하면 record 안에 record를 정의한다.
- 기본값이 필요하면 compact 생성자에서 채운다. 클래스로 바꾸지 않는다.

```java
// Request — 클라이언트가 보내는 형태
public record PropertySearchRequest(
        String district,
        ContractType contractType,
        Long depositMin,
        Long depositMax,
        Double radiusKm,
        @Max(100) Integer size,
        String cursor
) { }

// Condition — 매퍼가 필요로 하는 형태
// 커서는 식별자로, 반경은 바운딩 박스 좌표로 변환된 뒤 전달된다
public record PropertySearchCondition(
        String district,
        ContractType contractType,
        Long depositMin,
        Long depositMax,
        Double minLat, Double maxLat,
        Double minLng, Double maxLng,
        Long lastId,
        int limit
) { }

// Response — 변환은 정적 팩토리로
public record PropertyResponse(
        Long propertyId,
        String district,
        Long deposit,
        RiskGrade riskGrade
) {
    public static PropertyResponse from(Property entity) { ... }
}
```

---

## Repository (JPA)

- **INSERT·UPDATE·DELETE는 전부 JPA가 담당한다.** 경계 판단 기준은 영속성 구조 문서를 따른다.
- 엔티티를 반환하는 조회와 모든 변경을 담당한다.
- 등록은 `save`, 수정은 조회한 엔티티의 변경 메서드 호출로 처리한다. 변경 감지가 UPDATE를 수행하므로 `save`를 다시 호출하지 않는다.
- 벌크 연산이 필요하면 `@Modifying` 쿼리를 사용하되, 영속성 컨텍스트를 우회하므로 실행 후 상태를 신뢰하지 않는다.
- 메서드 이름으로 표현되는 조회만 작성한다. 복잡해지면 매퍼로 옮긴다.
- 화면에 전달할 목록을 조회하지 않는다.

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
```

---

## Mapper (MyBatis)

- **조회 전용이다. INSERT·UPDATE·DELETE를 작성하지 않는다.** 쓰기는 JPA가 담당한다.
- DTO만 반환한다. 엔티티를 반환하지 않는다.
- 파라미터는 Condition 객체를 받는다. `@Param`으로 여러 개를 나열하지 않는다.
- `SELECT`에 화면이 필요로 하는 컬럼만 적는다. `*`를 쓰지 않는다.
- 목록 조회는 커서 방식을 사용한다. OFFSET을 쓰지 않는다.
- 동적 조건은 `<where>`, `<if>`로 작성한다.
- 메서드를 추가하면 테스트를 같은 변경 단위에 포함한다.

```java
public interface PropertyMapper {
    List<PropertyMarkerResponse> selectMarkers(PropertySearchCondition cond);
    List<DistrictCountResponse> selectDistrictCounts(PropertySearchCondition cond);
}
```

---

## Service

반환 타입 기준에 맞춰 두 개로 나눈다. 한쪽이 필요 없으면 만들지 않는다.

| 클래스 | 사용 도구 | 트랜잭션 |
| --- | --- | --- |
| `<도메인>QueryService` | Mapper (DTO 반환) | `@Transactional(readOnly = true)` 클래스 레벨 |
| `<도메인>CommandService` | Repository (엔티티) | `@Transactional` 메서드 레벨 |

### 규칙

- 컨트롤러에 비즈니스 로직을 두지 않는다. 서비스가 판단하고 컨트롤러는 위임만 한다.
- 판정·계산 로직은 서비스에서 수행한다. 저장 프로시저·트리거로 옮기지 않는다.
- 계산·판정 규칙이 복잡하면 별도 클래스로 분리한다. 조건 분기가 여러 갈래이거나, 산식이 여러 단계이거나, 경계값 검증이 필요한 로직이 대상이다.
  분리한 클래스는 스프링 빈이 아니어도 되며, 입력과 출력만 갖는 순수 함수로 작성한다. 저장소나 외부 연동에 의존하지 않으므로 테스트가 쉬워진다.
  이름은 역할이 드러나게 짓는다. `<대상><동작>` 형태를 기본으로 하되 형식에 얽매이지 않는다. `RiskGradeCalculator`, `LoanLimitCalculator`가 그 예다.
- 하나의 CommandService 메서드가 하나의 트랜잭션 경계다. 서비스끼리 호출해 트랜잭션을 중첩시키지 않는다.
- 외부 API 호출을 트랜잭션 안에 두지 않는다. 커넥션을 오래 점유한다.
- 동일 트랜잭션에서 JPA로 변경한 데이터를 매퍼로 조회하지 않는다. 반영되지 않은 값을 읽는다.

---

## Controller

- 요청 검증, 서비스 호출, 응답 반환만 한다.
- 응답은 `ApiResponse<T>`로 감싼다. DTO를 직접 반환하지 않는다.
- 성공 응답은 `ApiResponse<T>`를 그대로 반환한다. `ResponseEntity`로 한 번 더 감싸지 않는다.
- 200이 아닌 성공 상태는 `@ResponseStatus`로 지정한다. 등록은 201, 삭제는 204.
- `ResponseEntity`는 헤더를 직접 제어해야 할 때만 사용한다.
- 경로와 필드명은 API 명세서에 있는 것만 사용한다. 없는 경로를 만들지 않는다.
- **컨트롤러는 자원 경로 단위로 나눈다.** 도메인이 같아도 경로가 다르면 분리한다. 관심 매물은 `/api/me/wishlist`이므로 `/api/properties`를 담당하는 컨트롤러에 두지 않는다.
- 인증 사용자는 `@AuthenticationPrincipal`로 받는다. 요청 본문에서 사용자 식별자를 받지 않는다.
- 입력 검증은 `@Valid`와 Bean Validation 애노테이션으로 처리한다.

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/properties")
public class PropertyController {

    private final PropertyQueryService queryService;

    @GetMapping
    public ApiResponse<CursorPage<PropertyResponse>> search(
            @Valid @ModelAttribute PropertySearchRequest request) {
        return ApiResponse.ok(queryService.search(request.toCondition()));
    }
}

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/me/wishlist")
public class WishlistController {

    private final WishlistQueryService queryService;
    private final WishlistCommandService commandService;

    @GetMapping
    public ApiResponse<CursorPage<WishlistResponse>> list(
            @AuthenticationPrincipal Long userId,
            @Valid @ModelAttribute CursorRequest request) {
        return ApiResponse.ok(queryService.findByUser(userId, request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> add(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody WishlistAddRequest request) {
        commandService.add(userId, request.propertyId());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{propertyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ApiResponse<Void> remove(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long propertyId) {
        commandService.remove(userId, propertyId);
        return ApiResponse.ok();
    }
}
```

---

## 공통 응답

```java
public record ApiResponse<T>(boolean success, T data, ErrorResponse error) {
    public static <T> ApiResponse<T> ok(T data) { ... }
    public static ApiResponse<Void> ok() { ... }
    public static ApiResponse<Void> fail(ErrorCode code, String field) { ... }
}

public record CursorPage<T>(List<T> items, String nextCursor, boolean hasNext) { }

public record ErrorResponse(String code, String message, String field) { }
```

- 성공 시 `success: true`, `data`에 결과.
- 실패 시 `success: false`, `error`에 사유.
- 목록은 `CursorPage`로 감싼다. 전체 건수를 반환하지 않는다.

---

## 예외 처리

### ErrorCode

오류 코드는 **단일 enum**으로 관리한다. 도메인별로 파일을 나누지 않는다. 오류 코드는 클라이언트와의 계약이므로 한곳에 모여 있어야 전체를 훑고 중복·누락을 확인할 수 있다.

- 도메인별로 구역을 나누고 주석을 단다.
- 접두사로 도메인을 구분한다. `AUTH_`, `USER_`, `PROPERTY_`, `RISK_`, `LOAN_`, `CONSULT_`
- **API 명세서의 오류 코드 표와 1:1로 대응시킨다.** 코드명과 상태 코드를 임의로 만들지 않는다.
- 오류 상태 코드는 ErrorCode가 갖고 전역 처리기가 적용한다. 컨트롤러에서 지정하지 않는다.

```java
public enum ErrorCode {
    // 인증
    AUTH_TOKEN_EXPIRED(401, "액세스 토큰이 만료되었습니다."),
    AUTH_INVALID_CREDENTIAL(401, "인증 정보가 일치하지 않습니다."),
    AUTH_FORBIDDEN(403, "접근 권한이 없습니다."),

    // 회원
    USER_DUPLICATED(409, "이미 가입된 계정입니다."),
    PROFILE_INCOMPLETE(422, "대출 한도 계산에 필요한 자격 정보가 없습니다."),

    // 매물
    PROPERTY_NOT_FOUND(404, "존재하지 않는 매물입니다."),
    WISHLIST_DUPLICATED(409, "이미 등록된 관심 매물입니다."),

    // 위험도
    RISK_NOT_ANALYZED(404, "아직 분석되지 않은 매물입니다."),
    RISK_REANALYZE_TOO_SOON(429, "재분석은 잠시 후 다시 요청할 수 있습니다."),

    // 대출
    LOAN_PROPERTY_NOT_ELIGIBLE(422, "보증보험 가입이 불가한 매물입니다."),

    // 상담
    CONSULT_QUOTA_EXCEEDED(429, "잠시 후 다시 시도해 주세요."),

    // 외부 연동
    EXTERNAL_API_UNAVAILABLE(503, "일시적으로 조회할 수 없습니다.");

    private final int status;
    private final String message;
}
```

### 예외

```java
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String field;
}
```

- 예외는 `BusinessException(ErrorCode)`으로만 던진다. `RuntimeException`을 직접 던지지 않는다.
- 도메인별 예외 클래스를 만들지 않는다. 구분은 ErrorCode가 담당한다.
- 전역 처리는 `@RestControllerAdvice` 한 곳에서 한다. 컨트롤러에 try-catch를 두지 않는다.
- 검증 실패(`MethodArgumentNotValidException`)도 전역 처리기에서 동일한 응답 형식으로 변환한다.
- 예상하지 못한 예외는 500으로 변환하되, 내부 메시지를 응답에 노출하지 않는다.