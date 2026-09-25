# 전월세 부동산 금융 플랫폼 — API 명세서 — 인프라

> 노출 엔드포인트, 알림 발송
> INF-01 · INF-05 (INF-05 행은 일부 구현 — 1장)
>
> ※ `docs/api/common.md`은 적용되지 않는다. 본 문서의 엔드포인트는 우리가 정의한 것이 아니라 Spring Actuator · exporter · Prometheus 등이 제공하는 고정 경로이며, 경로 규칙·응답 봉투·오류 코드 체계가 모두 도구를 따른다.
> ※ 인증 대신 **노출 범위**로 통제한다.
> ※ 서비스 API는 `docs/api/`의 도메인별 명세를, 포트별 접근 통제는 `docs/infra/system.md` 4장을, 관측 스택 질의와 설정 반영은 `docs/api/infra-observation.md`을 따른다.
> 작성 기준일 : 2026년 8월

## 1. 노출 엔드포인트

**INF-05 행은 일부만 구현됐다**(#192). 지금 노드에 있는 것은 **앱 두 슬롯의 `/actuator/prometheus`와 node · nginx · postgres · redis exporter 넷**이고(nginx exporter는 루프백 8088의 `stub_status`를 읽는다 — #209, 앱 지표는 #211), 호출 주체는 노드의 Prometheus agent다 — 긁은 지표와 로그는 Grafana Cloud로 보낸다(인프라 기술 스택 4.1). 표의 「호출 주체」 열 중 Prometheus는 agent를, Loki 행은 자체 호스팅을 전제한 것이다 — 지금 로그는 Vector가 Grafana Cloud의 Loki로 보내고 노드에 3100이 없다. Blackbox 행은 미구현이다. 앱 지표의 응답 시간은 SLO 구간 다섯(100ms · 300ms · 500ms · 1s · 3s)만 낸다 — 전체 히스토그램은 활성 시계열 한도(인프라 기술 스택 4.1)를 위협한다.

| 기능 | 대상 | 노드 | 포트 | 경로 | 호출 주체 | 내용 |
| --- | --- | --- | --- | --- | --- | --- |
| INF-01 | 애플리케이션 ×2 | APP-01 | 8081 · 8082 | /actuator/health/readiness | 배포 스크립트 | 슬롯 투입 가능 여부 |
| INF-05 | 애플리케이션 ×2 | APP-01 | 8081 · 8082 | /actuator/prometheus | Prometheus | 응답 시간, 5xx, Tomcat 스레드, HikariCP, JVM |
| INF-05 | node exporter | **APP-01 · DB-01 · DB-02** | 9100 | /metrics | Prometheus | CPU · 메모리 · 디스크 · 네트워크 |
| INF-05 | postgres exporter | APP-01 | 9187 | /metrics | Prometheus | 커넥션 수, 복제 지연, 슬로우 쿼리, 캐시 적중률 |
| INF-05 | redis exporter | APP-01 | 9121 | /metrics | Prometheus | 메모리, 축출 건수, 연결 수 |
| INF-05 | nginx exporter | APP-01 | 9113 | /metrics | Prometheus | 활성 · 읽기 · 쓰기 · 대기 연결, 수락 · 처리 연결 수, 누적 요청 수. **upstream 상태는 없다** — 오픈소스 Nginx `stub_status`가 내는 값은 이 일곱 가지뿐이다([ngx_http_stub_status_module](https://nginx.org/en/docs/http/ngx_http_stub_status_module.html)). 슬롯별 상태는 액세스 로그의 `upstream=`(Vector → Loki)로 본다 |
| INF-05 | Blackbox exporter | APP-01 | 9115 | /probe | Prometheus | 경로 도달 여부, 응답 시간 |
| INF-05 | Loki | APP-01 | 3100 | /loki/api/v1/push | Promtail | 애플리케이션 · Nginx · PostgreSQL 로그 |

- APP-01의 지점은 루프백에만 바인딩한다. 인터넷에 노출하지 않는다.
- **DB 노드의 5432는 이 표의 관측 지점이 아니다** — 앱 · 복제 경로이며 3노드에서는 DB-01 · DB-02 사설 IP에 게시하고 TLS만 받는다. 게시 주소와 접근 통제는 시스템 구성서 4장. **3노드의 DB 노드 node exporter는 그 노드의 수집기(`prom-agent-db`)가 긁어 직접 보낸다**(2026-09-25 · #243) — APP-01이 노드 밖에서 긁지 않으므로 9100을 열지 않는다
- **node exporter는 세 노드 모두에 둔다.** DB 노드의 디스크 사용률은 복제 슬롯 적체·WAL 아카이브 관련 알림과 시험의 판정 지표이며, APP-01의 지표로는 알 수 없다. DB 노드의 9100은 Prometheus가 사설 IP로 접근한다.
- **정기 작업 결과 지표**(node exporter textfile, 2026-09-25 · #243) — DB 노드의 `/var/lib/rental-metrics/*.prom`을 node exporter가 읽는다. `rental_job_last_success_timestamp_seconds{task="wal_ship|logical_backup|physical_backup"}`(마지막 성공 시각, epoch 초 — 성공으로 끝날 때만 바뀐다, standby의 「아무것도 안 함」은 쓰지 않는다) · `rental_wal_ship_pending_files`(아직 보내지 못한 WAL 파일 수). 라벨 이름이 `job`이 아니라 `task`인 것은 수집 때 붙는 `job="node"`와 겹치지 않게다. 권장 알림은 운영 절차서 2장
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

> **미구현 — INF-05**. 자체 호스팅 관측 스택을 전제로 썼다 — 관측 작업에서 노드 밖 전송 기준으로 다시 본다. 그 전까지 지금 작업의 근거로 쓰지 않는다.

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

> **미구현 — INF-05**. 자체 호스팅 관측 스택을 전제로 썼다 — 관측 작업에서 노드 밖 전송 기준으로 다시 본다. 그 전까지 지금 작업의 근거로 쓰지 않는다.

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