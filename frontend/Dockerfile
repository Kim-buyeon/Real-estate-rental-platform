# 프론트 이미지. 빌드 산출물(정적 파일)만 Nginx 로 서빙한다.
# 빌드 컨텍스트는 frontend/ 다 —
#   docker build --build-arg VITE_KAKAO_MAP_KEY=... -t rental-frontend frontend

# ── 빌드 ─────────────────────────────────────────────────────────────────
FROM node:22-alpine AS build
WORKDIR /workspace

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# 카카오맵 JavaScript 키는 번들에 들어가는 공개 값이다(.env.example). 빌드 시점에 넣는다.
# Vite 는 process.env 의 VITE_ 변수도 읽으므로 컨텍스트 밖의 루트 .env 없이 이 값으로 빌드된다.
ARG VITE_KAKAO_MAP_KEY
ENV VITE_KAKAO_MAP_KEY=${VITE_KAKAO_MAP_KEY}
RUN npm run build

# ── 실행 ─────────────────────────────────────────────────────────────────
# 1.26 계열은 지원이 끝났고 그 이미지의 OpenSSL 에 CRITICAL(CVE-2026-31789)이 남아 있다 — 현재 stable 계열을 쓴다
FROM nginx:1.30-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80
