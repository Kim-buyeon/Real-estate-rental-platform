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

# ── 실행 ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# root 로 돌리지 않는다
RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /workspace/app.war app.war
USER app

# 슬롯 포트는 SERVER_PORT 로 덮는다(8081 · 8082) — 시스템 구성서 4장
EXPOSE 8080
# exec 형식 — SIGTERM 이 JVM 에 바로 닿아야 graceful 종료(application.yml server.shutdown)가 동작한다
ENTRYPOINT ["java", "-jar", "app.war"]
