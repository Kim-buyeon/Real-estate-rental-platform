# 전월세 부동산 금융 플랫폼 — API 명세서 — 관측

> 지표 질의, 로그 질의, 설정 반영
> INF-05
>
> ※ `docs/api/common.md`은 적용되지 않는다. 본 문서의 엔드포인트는 Prometheus · Alertmanager · Loki가 제공하는 고정 경로이며, 경로 규칙·응답 형식이 모두 도구를 따른다.
> ※ 애플리케이션과 exporter가 노출하는 지점은 `docs/api/infra.md`를, 지표·알림 규칙의 정의는 `docs/infra/observability.md`를 따른다.
> ※ 전 경로가 루프백이며 인터넷에 노출하지 않는다.
> 작성 기준일 : 2026년 8월

## 1. 지표 질의

| 대상 | 메서드 | 경로 | 설명 | 호출 주체 |
| --- | --- | --- | --- | --- |
| Prometheus | GET | /api/v1/query | 즉시 질의 | 배포 관찰 판정 스크립트 |
| Prometheus | GET | /api/v1/query_range | 구간 질의 | 시험 결과 수집 스크립트 · Grafana |
| Prometheus | GET | /api/v1/rules | 로드된 알림 규칙 조회 | 규칙 변경 후 확인 |

- 기본 주소는 `http://127.0.0.1:9090`이다.
- 배포 관찰 판정에 사용하는 질의 목록은 `docs/infra/runbook.md` 3.3절을 따른다.

### 1.1 즉시 질의

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| query | 문자열 | PromQL 표현식 |
| time | 문자열 | 평가 시각. 생략 시 현재 |

GET /api/v1/query?query=count(up{job="app"}==1) — 응답

```json
{
  "status": "success",
  "data": {
    "resultType": "vector",
    "result": [
      {
        "metric": {},
        "value": [1791429120, "2"]
      }
    ]
  }
}
```

- `result`가 빈 배열이면 해당 지표가 존재하지 않는다는 뜻이며, 오류가 아니다. 값이 0인 것과 구분해야 한다.

### 1.2 구간 질의

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| query | 문자열 | PromQL 표현식 |
| start · end | 문자열 | 구간 시작·종료 시각 |
| step | 문자열 | 표본 간격 |

GET /api/v1/query_range?query=…&start=1791428520&end=1791429120&step=15s — 응답

```json
{
  "status": "success",
  "data": {
    "resultType": "matrix",
    "result": [
      {
        "metric": { "job": "app" },
        "values": [
          [1791428520, "0.184"],
          [1791428535, "0.192"]
        ]
      }
    ]
  }
}
```

- `step`은 스크레이프 주기(15초) 이상으로 둔다. 더 짧게 잡으면 없는 표본을 보간한 값이 나온다.

### 1.3 규칙 조회

GET /api/v1/rules — 응답

```json
{
  "status": "success",
  "data": {
    "groups": [
      {
        "name": "infra",
        "rules": [
          {
            "name": "ReplicationLag",
            "state": "inactive",
            "health": "ok",
            "duration": 60,
            "labels": { "severity": "critical" }
          }
        ]
      }
    ]
  }
}
```

| 필드 | 값 | 의미 |
| --- | --- | --- |
| state | inactive · pending · firing | 현재 발화 상태 |
| health | ok · err · unknown | 평가 성공 여부 |
| duration | 정수 | `for` 지속 시간(초) |

- **`health`가 `ok`가 아닌 규칙은 평가에 실패하고 있으므로 알림이 발생하지 않는다.**

---

## 2. 로그 질의

| 대상 | 메서드 | 경로 | 설명 | 호출 주체 |
| --- | --- | --- | --- | --- |
| Loki | GET | /loki/api/v1/query_range | 로그 구간 질의 | Grafana |

- 기본 주소는 `http://127.0.0.1:3100`이다.
- 스크립트가 직접 호출하지 않는다. 조사는 Grafana 대시보드에서 수행한다.
- 로그 적재 경로(`/loki/api/v1/push`)는 `docs/api/infra.md`를 따른다.

GET /loki/api/v1/query_range?query={job="app"} |= "ERROR" — 응답

```json
{
  "status": "success",
  "data": {
    "resultType": "streams",
    "result": [
      {
        "stream": { "job": "app", "level": "ERROR" },
        "values": [
          ["1791429120000000000", "{\"traceId\":\"a3f9\",\"message\":\"…\"}"]
        ]
      }
    ]
  }
}
```

- 타임스탬프 단위가 **나노초**다. Prometheus의 초 단위와 다르다.

---

## 3. 설정 반영

| 대상 | 메서드 | 경로 | 설명 |
| --- | --- | --- | --- |
| Prometheus | POST | /-/reload | 스크레이프 설정·알림 규칙 반영 |
| Alertmanager | POST | /-/reload | 라우팅·수신자 설정 반영 |

- 기본 주소는 Prometheus 9090, Alertmanager 9093이다.
- Prometheus는 `--web.enable-lifecycle` 플래그를 켜야 `/-/reload`가 열린다. Alertmanager는 기본으로 활성이다.
- 성공 시 200, 본문은 비어 있다.

### 3.1 알림 규칙 반영 절차

Prometheus는 **기동 시점에만 규칙 파일을 읽는다.** 파일을 고쳐도 이미 떠 있는 프로세스는 메모리에 올려둔 옛 규칙을 계속 평가하며, 오류를 발생시키지 않는다.

| 순서 | 명령 | 확인하는 것 |
| --- | --- | --- |
| 1 | `promtool check rules alert.rules.yml` | 문법 |
| 2 | `POST /-/reload` | 반영 |
| 3 | `GET /api/v1/rules` | 로드 여부와 `health` |

**3단계를 생략하지 않는다.** `promtool`은 문법만 보고, 떠 있는 Prometheus가 그 파일을 읽었는지는 알려주지 않는다.

### 3.2 재시작을 쓰지 않는 이유

재시작해도 설정은 반영되지만 두 가지를 잃는다.

| 손실 | 결과 |
| --- | --- |
| 스크레이프 중단 | WAL 재생까지 수십 초. 지표에 빈 구간이 남는다 |
| 발화 대기 상태 초기화 | `for` 지속 시간을 세던 상태가 사라져 **진행 중인 장애의 알림이 그만큼 미뤄진다** |

복제 지연 알림은 `for: 60s`이므로, 50초 경과 시점에 재시작하면 발화가 60초 더 늦어진다.