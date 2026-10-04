"""측정 모드 추적 설정(infra/otel/agent-measure.yaml)의 메서드 구간 목록을 만든다 (INF-06, #378).

백엔드 소스에서 아래 패키지의 클래스와 그 클래스가 선언한 메서드 이름을 모아, agent-measure.yaml 의
`# BEGIN methods` ~ `# END methods` 사이를 바꿔 쓴다. 에이전트 2.31.1 의 구조형 설정
(`instrumentation/development.java.methods.include` — class · methods[].name) 꼴이다.

  controller  — 요청 진입. 응답 봉투 만들기 · 검증 실패 처리가 여기서 보인다(#390)
  service     — 요청이 쓰는 업무 로직. "어느 함수가 시간을 먹나"의 단위
  calculator  — 판정 · 한도 계산
  store       — Redis 를 감싼 저장소. 추적에 Redis 구간이 없어(#352) 이 메서드 구간이 Redis 시간을 대신한다
  external    — 외부 API 호출

더하는 것 — THIRD_PARTY 에 적은 우리 소스 밖 클래스의 메서드(BCrypt 대조 · 해싱).

빼는 것 — EXCLUDE 에 적은 메서드. 로컬 실측에서 요청 하나에 수십 번 넘게 불려(행마다) 구간이 쏟아지고
구간 자체의 비용이 측정을 흐리는 것이다. 그 비용은 프로파일러(chaos-harness/measure/node/profiler.sh)가 본다.

  python chaos-harness/measure/tools/gen-methods.py          # 파일을 고친다
  python chaos-harness/measure/tools/gen-methods.py --check  # 바뀔 것이 있으면 1 로 끝난다
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "backend/src/main/java"
BASE = "com/duri/rentalplatform"
TARGET = ROOT / "infra/otel/agent-measure.yaml"
LAYERS = ("controller", "service", "calculator", "store")
EXTERNAL = "external"
# 위 패키지 밖에서 Redis 를 직접 쓰는 클래스 — Redis 시간을 빠짐없이 구간으로 잡는다
# (RedisTemplate · 락을 쓰는 클래스를 저장소 전체에서 찾은 결과, #378. config/ 의 설정 · 구독 시작은 요청 경로가 아니다)
EXTRA = (
    "common/security/StreamTicketStore.java",
    "common/lock/DistributedLockAspect.java",
    "domain/notification/sender/SseNotificationSender.java",
    # 요청마다 지나는 필터 — 토큰 검증 · 요청 ID. #390 로컬 스모크에서 빠른 경로(상세 · 알림)의 「설명되지 않은 시간」이
    # 48 ~ 67% 였다 — Controller · 필터 · JSON 변환이 구간 밖이어서다(양식 [7.1] 은 5% 넘으면 계측 공백)
    "common/security/JwtAuthenticationFilter.java",
    "common/web/RequestIdFilter.java",
)

# 행마다 불려 구간이 쏟아지는 메서드 — "클래스 단순 이름.메서드". 로컬 실측(#378, 2026-10-03 — 로컬 매물 67,683건,
# 업무 요청 18회 + 기동 따라잡기 배치 1회)에서 추적 하나에 100번 넘게 불린 것이다. 기동 배치 한 번이 구간 26만 개를 냈고
# 그중 이 목록이 대부분이었다. 지도 마커의 거리 계산(haversineKm)은 요청 하나에 498 ~ 1,003번이다.
# 기준 100 — 묶음(chunk) 단위 메서드(saveChunk · saveAll 70번)는 남기고 행 단위만 뺀다. 빠진 함수의 비용은 프로파일러가 본다.
EXCLUDE = {
    "MockRentTransactionClient.roundToUnit", "MockSaleTransactionClient.roundToUnit",
    "MockRentTransactionClient.seedOf", "MockSaleTransactionClient.seedOf",
    "MockRentTransactionClient.findRentTransactions", "MockSaleTransactionClient.findSaleTransactions",
    "PropertyLoadWriter.findCode",
    "LandlordNameGenerator.mix", "LandlordNameGenerator.fold", "LandlordNameGenerator.naturalKeyText",
    "LandlordNameGenerator.derive", "LandlordNameGenerator.generate",
    "PropertyLoadService.toPropertyType", "PropertyLoadService.composeRawAddress", "PropertyLoadService.basePriceDate",
    "PropertyLoadService.toRegistration", "PropertyLoadService.toRegistrationSafely",
    "PropertyLoadService.missingRequiredField",
    "MockGeocodeClient.ratio", "MockGeocodeClient.scaled", "MockGeocodeClient.geocode",
    "MockAddressNormalizeClient.normalize",
    "MarketPriceCalculator.find", "MarketPriceCalculator.isSample", "MarketPriceCalculator.toPropertyType",
    "ContractTypeClassifier.classify",
    "GeoDistanceCalculator.haversineKm",
}
# 실데이터 클라이언트의 항목 단위 해석 함수 — 로컬은 Mock 이라 돌지 않아 실측이 없다. 응답 항목(행)마다 불리는
# 모양이라 소스를 보고 뺐다(정적 판단). 요청 단위(request · fetch · parse)는 남겨 외부 호출 시간은 구간으로 본다.
EXCLUDE |= {
    "RealRentTransactionClient.toTransaction", "RealSaleTransactionClient.toTransaction",
    "RtmsXmlSupport.readItems", "RtmsXmlSupport.readDealDate", "RtmsXmlSupport.readAmount",
    "RtmsXmlSupport.readDecimal", "RtmsXmlSupport.readInteger", "RtmsXmlSupport.readText",
    "RtmsXmlSupport.firstNonBlank",
    "RealBuildingLedgerClient.xmlTexts", "RealBuildingLedgerClient.items", "RealBuildingLedgerClient.select",
    "RealBuildingLedgerClient.narrow", "RealBuildingLedgerClient.roadPart", "RealBuildingLedgerClient.toDocument",
    "RealBuildingLedgerClient.ledgerAddress", "RealBuildingLedgerClient.collapse", "RealBuildingLedgerClient.TitleRow",
    "RealBuildingLedgerClient.from", "RealBuildingLedgerClient.text", "RealBuildingLedgerClient.area",
    "RealBuildingLedgerClient.date",
    "RealJeonseLoanRateClient.itemsOf", "RealJeonseLoanRateClient.toRate", "RealJeonseLoanRateClient.decimalOf",
    # Mock 의 같은 자리(구 · 달마다 한 번 — 추적 하나에 575번)와 같은 모양이다
    "RealRentTransactionClient.findRentTransactions", "RealSaleTransactionClient.findSaleTransactions",
    "FaultRentTransactionClient.findRentTransactions", "FaultSaleTransactionClient.findSaleTransactions",
    # 시세 계산의 표본 모음 — 매물(행)마다 더한다
    "MarketPriceCalculator.Sample.add", "MarketPriceCalculator.Sample.toMarketPrice", "MarketPriceCalculator.Sample.median",
}

# 우리 소스가 아닌 클래스 — 소스에서 찾지 못하니 클래스 · 메서드를 직접 적는다. "이진 이름": [메서드].
# BCrypt 대조 · 해싱(로그인 · 가입 · 비밀번호 재설정) — 일부러 느린 연산이라 로그인 시간의 대부분이 여기 있을 수 있다(#390).
# 에이전트는 적은 클래스(와 하위 클래스)가 **선언한** 메서드에만 구간을 붙인다(2.31.1 MethodInstrumentation — hasSuperType ·
# isMethod().and(namedOneOf)). matches · encode 는 상위 AbstractValidatingPasswordEncoder 의 final 메서드라 이 클래스 이름으로는
# 붙지 않는다 — 실제 계산을 하는 BCryptPasswordEncoder 의 matchesNonNull · encodeNonNullPassword 를 적는다
# (spring-security-crypto 7.1.1 소스 jar 로 확인. 판을 올리면 이름을 다시 본다)
THIRD_PARTY = {
    "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder": ["matchesNonNull", "encodeNonNullPassword"],
    # 응답 JSON 변환(spring-web 7.0.9 AbstractJacksonHttpMessageConverter.writeInternal)은 넣어 봤으나 구간이 0 이었다(#390 로컬).
    # 그 시간은 필터 구간의 자기 시간에 남는다 — 집계가 「Controller 밖」으로 적는다(analyze/pipeline.py WRAPPER_ROW_NAMES)
}

# 메서드 선언 — 접근 제어자 · static 등 뒤에 반환형과 이름, 여는 괄호. 생성자 · 람다 · 호출은 걸리지 않게 한다
DECL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:(?:public|protected|private|static|final|synchronized|default|abstract)\s+)*"
    r"(?:<[^>]+>\s+)?"
    r"[\w.<>\[\],? ]+?\s+(\w+)\s*\(",
    re.M,
)
KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "new", "else", "try", "throw", "synchronized"}
THROWS = re.compile(r"\s*(?:throws\s+[\w.,\s<>]+)?\s*\{")


def is_declaration(text, m):
    """호출 · 생성 식이 아니라 선언인지 — 앞 낱말이 키워드가 아니고, 괄호를 닫은 뒤 (throws …) { 가 온다."""
    # 이름이 있는 줄만 본다 — 윗줄 어노테이션 인자(key = "…")의 = 를 대입으로 보지 않게
    line = text[text.rfind("\n", 0, m.start(1)) + 1 : m.start(1)]
    words = line.split()
    if not words or words[-1] in KEYWORDS or "=" in line:
        return False
    depth, i = 0, m.end() - 1
    while i < len(text):
        depth += {"(": 1, ")": -1}.get(text[i], 0)
        if depth == 0:
            break
        i += 1
    return bool(THROWS.match(text, i + 1))


TYPE = re.compile(r"\b(class|interface|record|enum)\s+(\w+)[^;{]*\{")


def blank_literals(text):
    """주석 · 문자열 · 문자 리터럴을 같은 길이의 # 로 — 중괄호를 세는 데 섞이지 않게(위치는 그대로 둔다).
    공백으로 채우면 선언 정규식이 긴 공백에서 되짚기를 거듭해 멈춘 듯 느려진다(#378 실측)."""
    pat = re.compile(r'//[^\n]*|/\*.*?\*/|"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'', re.S)
    return pat.sub(lambda m: re.sub(r"[^\n]", "#", m.group(0)), text)


def types(code):
    """형 선언마다 (종류, 이진 이름 경로, 본문 시작, 본문 끝) — 안쪽 클래스는 Outer$Inner 로 잡는다."""
    found = []
    for m in TYPE.finditer(code):
        open_at = m.end() - 1
        depth, i = 0, open_at
        while i < len(code):
            depth += {"{": 1, "}": -1}.get(code[i], 0)
            if depth == 0:
                break
            i += 1
        found.append([m.group(1), m.group(2), open_at, i])
    for t in found:  # 바깥 형 이름을 앞에 붙인다(가장 가까운 것부터)
        outers = [o for o in found if o is not t and o[2] < t[2] and t[3] <= o[3]]
        outers.sort(key=lambda o: o[2])
        t.append("$".join([o[1] for o in outers] + [t[1]]))
    return found


def classes():
    for path in sorted((SRC / BASE).rglob("*.java")):
        parts = path.relative_to(SRC / BASE).parts
        in_layer = len(parts) >= 3 and parts[0] == "domain" and parts[2] in LAYERS
        in_external = parts[0] == EXTERNAL
        in_extra = "/".join(parts) in EXTRA
        if not (in_layer or in_external or in_extra):
            continue
        text = path.read_text(encoding="utf-8")
        code = blank_literals(text)
        pkg = ".".join(path.relative_to(SRC).parent.parts)
        found = types(code)
        # 맨 바깥 형이 class 가 아니면(인터페이스 · record · enum) 그 파일은 건너뛴다 — 인터페이스는 구현 클래스에서 잡는다
        if not found or found[0][0] != "class":
            continue
        by_type: dict[str, list[str]] = {}
        for m in DECL.finditer(code):
            name = m.group(1)
            if name in KEYWORDS or not is_declaration(code, m):
                continue
            # 안쪽 record 의 머리(record X(...) {)는 선언 정규식에 메서드처럼 걸린다 — 형 이름이면 뺀다
            if any(t[1] == name and t[0] != "class" for t in found):
                continue
            # 선언을 품은 가장 안쪽 형 — 메서드는 그 형의 이진 이름(Outer$Inner) 소속이다
            owner = min((t for t in found if t[2] < m.start(1) < t[3]), key=lambda t: t[3] - t[2], default=None)
            if owner is None or owner[0] != "class" or name == owner[1]:
                continue  # record · enum · 인터페이스 안의 것, 생성자는 뺀다
            if f"{owner[4].replace('$', '.')}.{name}" in EXCLUDE or f"{owner[1]}.{name}" in EXCLUDE:
                continue
            names = by_type.setdefault(owner[4], [])
            if name not in names:
                names.append(name)
        for binary, names in by_type.items():
            yield f"{pkg}.{binary}", names
    yield from THIRD_PARTY.items()


def render():
    lines = ["# BEGIN methods — chaos-harness/measure/tools/gen-methods.py 가 만든다. 손으로 고치지 않는다"]
    lines += ["instrumentation/development:", "  java:", "    methods:", "      include:"]
    for fqcn, names in classes():
        lines.append(f"        - class: {fqcn}")
        lines.append("          methods:")
        lines += [f"            - name: {n}" for n in names]
    lines.append("# END methods")
    return "\n".join(lines)


def main():
    text = TARGET.read_text(encoding="utf-8")
    new = re.sub(r"# BEGIN methods.*?# END methods", lambda _: render(), text, flags=re.S)
    if "--check" in sys.argv:
        sys.exit(0 if new == text else 1)
    TARGET.write_text(new, encoding="utf-8", newline="\n")
    count = new.count("- class:")
    sys.stdout.reconfigure(encoding="utf-8")
    print(f"{TARGET.relative_to(ROOT)} — 클래스 {count}개, 메서드 {new.count('- name:')}개")


if __name__ == "__main__":
    main()
