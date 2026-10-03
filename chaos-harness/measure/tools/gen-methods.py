"""측정 모드 추적 설정(infra/otel/agent-measure.yaml)의 메서드 구간 목록을 만든다 (INF-06, #378).

백엔드 소스에서 아래 패키지의 클래스와 그 클래스가 선언한 메서드 이름을 모아, agent-measure.yaml 의
`# BEGIN methods` ~ `# END methods` 사이를 바꿔 쓴다. 에이전트 2.31.1 의 구조형 설정
(`instrumentation/development.java.methods.include` — class · methods[].name) 꼴이다.

  service     — 요청이 쓰는 업무 로직. "어느 함수가 시간을 먹나"의 단위
  calculator  — 판정 · 한도 계산
  store       — Redis 를 감싼 저장소. 추적에 Redis 구간이 없어(#352) 이 메서드 구간이 Redis 시간을 대신한다
  external    — 외부 API 호출

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
LAYERS = ("service", "calculator", "store")
EXTERNAL = "external"
# 위 패키지 밖에서 Redis 를 직접 쓰는 클래스 — Redis 시간을 빠짐없이 구간으로 잡는다
# (RedisTemplate · 락을 쓰는 클래스를 저장소 전체에서 찾은 결과, #378. config/ 의 설정 · 구독 시작은 요청 경로가 아니다)
EXTRA = (
    "common/security/StreamTicketStore.java",
    "common/lock/DistributedLockAspect.java",
    "domain/notification/sender/SseNotificationSender.java",
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
    words = m.group(0)[: m.start(1) - m.start(0)].split()
    if not words or words[-1] in KEYWORDS or "=" in m.group(0):
        return False
    depth, i = 0, m.end() - 1
    while i < len(text):
        depth += {"(": 1, ")": -1}.get(text[i], 0)
        if depth == 0:
            break
        i += 1
    return bool(THROWS.match(text, i + 1))


def classes():
    for path in sorted((SRC / BASE).rglob("*.java")):
        parts = path.relative_to(SRC / BASE).parts
        in_layer = len(parts) >= 3 and parts[0] == "domain" and parts[2] in LAYERS
        in_external = parts[0] == EXTERNAL
        in_extra = "/".join(parts) in EXTRA
        if not (in_layer or in_external or in_extra):
            continue
        text = path.read_text(encoding="utf-8")
        # 맨 바깥 형(들여쓰기 없는 첫 선언)만 본다 — 안에 둔 record 때문에 클래스를 빼지 않게
        top = re.search(r"^(?:public\s+|final\s+|abstract\s+|sealed\s+)*(class|interface|record|enum|@interface)\s", text, re.M)
        if not top or top.group(1) != "class":
            continue  # 인터페이스는 구현 클래스에서 잡는다. record · enum 은 값이다
        fqcn = ".".join(path.relative_to(SRC).with_suffix("").parts)
        simple = path.stem
        names = []
        for m in DECL.finditer(text):
            name = m.group(1)
            if name in KEYWORDS or name == simple or f"{simple}.{name}" in EXCLUDE:
                continue
            if not is_declaration(text, m):
                continue
            if name not in names:
                names.append(name)
        if names:
            yield fqcn, names


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
