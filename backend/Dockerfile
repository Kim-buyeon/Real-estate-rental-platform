# 백엔드 이미지. 빌드와 실행을 나눠 실행 이미지에 JDK · Gradle · 소스를 싣지 않는다.
# 빌드 컨텍스트는 backend/ 다 — docker build -t rental-backend backend

# ── 빌드 ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 해석을 소스보다 먼저 두어 소스만 바뀐 재빌드가 이 층을 재사용하게 한다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q > /dev/null

COPY src src
# 테스트는 CI 가 돌린다(ci.yml). 이미지 빌드에서 반복하지 않는다.
RUN ./gradlew bootWar --no-daemon -x test \
 && cp build/libs/*-SNAPSHOT.war app.war

# ── 추적 에이전트 ───────────────────────────────────────────────────────
# OpenTelemetry Java 에이전트를 받아 대조한다. 실행 단계가 아니라 여기서 받는 이유:
# 실행 층에는 대조를 마친 jar 한 개만 남기고, 실행 이미지에 curl 이 계속 있을지에 기대지 않는다.
# 빌드 단계와 같은 JDK 이미지라 새 베이스를 받지 않고, 별도 단계라 소스가 바뀌어도 이 층은 재사용된다.
# 대조 값은 Maven Central 이 jar 와 함께 내는 .sha1 파일의 값이다. ADD --checksum(sha256)을 쓰지 않는 이유 —
# URL 로 받은 파일은 권한이 0600 이라 app 사용자가 읽으려면 --chmod 가 더 들고, 받기 · 대조 · 권한을 한 RUN 에
# 두는 편이 읽기 쉽다. 해시가 다르면 sha1sum -c 가 실패해 빌드가 멈춘다.
FROM eclipse-temurin:21-jdk AS otel
ARG OTEL_AGENT_VERSION=2.31.1
ARG OTEL_AGENT_SHA1=1980f46fddc489e5c989d8369e655999455643ea
WORKDIR /otel
RUN curl -fsSL -o opentelemetry-javaagent.jar \
      "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar" \
 && echo "${OTEL_AGENT_SHA1}  opentelemetry-javaagent.jar" | sha1sum -c - \
 && chmod 0644 opentelemetry-javaagent.jar

# ── 실행 ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# root 로 돌리지 않는다
RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /workspace/app.war app.war
# 추적 에이전트. 소유는 root(COPY 기본), 권한 0644 — app 사용자는 읽기만 하고 바꿀 수 없다.
# -javaagent 를 줄 때만 동작한다 — 켜는 것은 운영 Compose(인프라 PR). 로컬 · CI 는 그대로다.
# 이미지가 jar 크기(약 24MB)만큼 커진다.
COPY --from=otel /otel/opentelemetry-javaagent.jar /app/otel/opentelemetry-javaagent.jar
USER app

# 슬롯 포트는 SERVER_PORT 로 덮는다(8081 · 8082) — 시스템 구성서 4장
EXPOSE 8080
# exec 형식 — SIGTERM 이 JVM 에 바로 닿아야 graceful 종료(application.yml server.shutdown)가 동작한다
ENTRYPOINT ["java", "-jar", "app.war"]
