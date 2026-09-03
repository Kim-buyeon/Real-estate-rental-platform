# 전월세 부동산 금융 플랫폼 — API 명세서 — 인프라

> 노출 엔드포인트, 알림 발송
> INF-01 · INF-05
>
> ※ `docs/api/common.md`은 적용되지 않는다. 본 문서의 엔드포인트는 우리가 정의한 것이 아니라 Spring Actuator · exporter · Prometheus 등이 제공하는 고정 경로이며, 경로 규칙·응답 봉투·오류 코드 체계가 모두 도구를 따른다.
> ※ 인증 대신 **노출 범위**로 통제한다.
> ※ 서비스 API는 `docs/api/`의 도메인별 명세를, 포트별 접근 통제는 `docs/infra/system.md` 4장을, 관측 스택 질의와 설정 반영은 `docs/api/observability.md`을 따른다.
> 작성 기준일 : 2026년 8월

## 1. 노출 엔드포인트

| 기능 | 대상 | 노드 | 포트 | 경로 | 호출 주체 | 내용 |
| --- | --- | --- | --- | --- | --- | --- |
| INF-01 | 애플리케이션 ×2 | APP-01 | 8081 · 8082 | /actuator/health/readiness | 배포 스크립트 | 슬롯 투입 가능 여부 |
| INF-05 | 애플리케이션 ×2 | APP-01 | 8081 · 8082 | /actuator/prometheus | Prometheus | 응답 시간, 5xx, Tomcat 스레드, HikariCP, JVM |
| INF-05 | node exporter | **APP-01 · DB-01 · DB-02** | 9100 | /metrics | Prometheus | CPU · 메모리 · 디스크 · 네트워크 |
| INF-05 | postgres exporter | APP-01 | 9187 | /metrics | Prometheus | 커넥션 수, 복제 지연, 슬로우 쿼리, 캐시 적중률 |
| INF-05 | redis exporter | APP-01 | 9121 | /metrics | Prometheus | 메모리, 축출 건수, 연결 수 |
| INF-05 | nginx exporter | APP-01 | 9113 | /metrics | Prometheus | 활성 연결, upstream 상태 |
| INF-05 | Blackbox exporter | APP-01 | 9115 | /probe | Prometheus | 경로 도달 여부, 응답 시간 |
| INF-05 | Loki | APP-01 | 3100 | /loki/api/v1/push | Promtail | 애플리케이션 · Nginx · PostgreSQL 로그 |

- APP-01의 지점은 루프백에만 바인딩한다. 인터넷에 노출하지 않는다.
- **node exporter는 세 노드 모두에 둔다.** DB 노드의 디스크 사용률은 복제 슬롯 적체·WAL 아카이브 관련 알림과 시험의 판정 지표이며, APP-01의 지표로는 알 수 없다. DB 노드의 9100은 Prometheus가 사설 IP로 접근한다.
- Nginx는 `/actuator`로 시작하는 경로를 차단한다.
- Prometheus 스크레이프 주기는 15초, Blackbox 프로브 주기는 30초다.
- 스크레이프 실패는 오류를 발생시키지 않고 해당 지표가 비어 있는 상태가 된다.

### 1.1 readiness 응답

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| status | 열거 | UP · DOWN · OUT_OF_SERVICE |

GET /actuator/health/readiness — 응답

```json
{
  "status": "UP"
}
```

- `/actuator/health`가 아니라 `readiness`를 사용한다. 전자는 DB 순단에도 DOWN을 반환한다.

### 1.2 Blackbox 프로브

다른 exporter와 달리 검사 대상을 파라미터로 받는다.

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| target | 문자열 | 검사할 URL. Nginx를 거친 실제 API 경로 |
| module | 열거 | http_2xx — 2xx 응답을 성공으로 판정 |

GET /probe?target=https://<서비스 도메인>/<검사 경로>&module=http_2xx — 응답

```
probe_success 1
probe_duration_seconds 0.184
probe_http_status_code 200
probe_ssl_earliest_cert_expiry 1.7924e+09
```

- 검사 대상은 헬스 엔드포인트가 아니라 실제 사용자 API 경로를 사용한다. 감시 대상은 **API 경로 3개**이며, 구체적인 경로는 미확정이고 1주차에 확정한다(`docs/infra/observability.md` 2장).

---

## 2. 알림 발송

| 경로 | 발신 | 수신 | 대상 |
| --- | --- | --- | --- |
| 내부 | Alertmanager | Slack 웹훅 + 이메일 | 지표 임계 초과 |
| 외부 | HetrixTools | Slack 웹훅 | 노드 도달 불가 |

| 심각도 | 채널 | 발송 | 이메일 |
| --- | --- | --- | --- |
| critical | #alert-critical | 즉시 | 켬 |
| warning | #alert-warning | 15분 묶음 | 끔 |
| info | #alert-warning | 일 1회 요약 | 끔 |

- HetrixTools는 Alertmanager를 거치지 않고 Slack으로 직접 발송한다. APP-01 정지 시 내부 알림 경로가 함께 정지하기 때문이다.
- **웹훅 URL은 채널마다 따로 발급한다.** Slack Incoming Webhook은 URL 하나가 채널 하나에 묶이며 요청 본문의 `channel` 값은 무시된다. `#alert-critical` · `#alert-warning` · HetrixTools용으로 3개가 필요하다.
- 웹훅 URL은 시크릿이며 저장소에 커밋하지 않는다.

### 2.1 Slack 페이로드

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| text | 문자열 | 알림 요약 |
| attachments[].color | 문자열 | 심각도 표시 |
| attachments[].fields | 배열 | 대상 · 발생 시각 · 조치 절차 |

채널은 웹훅 URL이 결정하므로 본문에 담지 않는다.

POST (Slack 웹훅) — 발송 본문

```json
{
  "text": "[CRITICAL] 복제 지연 60초 초과",
  "attachments": [
    {
      "color": "danger",
      "fields": [
        { "title": "대상", "value": "db-02", "short": true },
        { "title": "발생", "value": "2026-10-08 03:12", "short": true },
        { "title": "조치", "value": "운영 절차서 6.1 — standby 상태 확인", "short": false }
      ]
    }
  ]
}
```