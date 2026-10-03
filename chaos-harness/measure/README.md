# 부하 시험 측정(INF-06 #378)

한 회차를 같은 방법으로 재고 남기는 도구다. 시나리오 · JMeter 플랜은 여기 없다 — 사용자가 쓴다. 판정 기준(목표 p95 · 헤드룸 분모 · 회차 길이 · 크레딧 문턱)도 여기서 정하지 않는다 — 스크립트는 인자로 받는다.

```
measure/
├── node/          노드 · 생성기에서 도는 셸(이 문서의 순서)
├── report/        결과 보고서 양식
└── results/<회차>/  회차 결과 — 저장소가 무시한다
```

## 준비

- **SSH** — 사용자가 만든 `ssh_config`(별칭 `app01` · `db01` · `db02`, APP-02는 APP-01을 거친다). 경로는 `SSH_CONFIG`(기본 `C:/Users/bu200/loadtest-data/ssh_config`). 부하 생성기에서 돌리면 그 기계의 경로를 준다.
- **AWS CLI** — 크레딧 기록(`credits.sh`)만 쓴다. 읽기 전용. 없으면 크레딧 칸이 빈다.
- **어디서 돌리나** — `record.sh`는 **부하 생성기**(Linux)에서 돌린다. 생성기의 `vmstat` · `sar`를 함께 남기기 때문이다. 나머지는 SSH가 닿는 곳이면 어디서나 되지만, 결과가 한 폴더에 모이도록 같은 기계에서 돌린다.
- 노드 체크아웃은 측정 모드를 아는 커밋이어야 한다(`measure-mode.sh status`가 확인한다).

**「운영 작업」 표시가 붙은 단계는 서버 상태를 바꾼다 — 단계마다 사용자 확인을 받는다.** 나머지는 읽기와 노드 `/tmp` 쓰기뿐이다.

## 시험 시작 전 한 번

| 순서 | 명령 | 하는 일 | 운영 작업 |
| --- | --- | --- | --- |
| 1 | `BIND_ADDR=<생성기 사설 IP> bash node/trace-receiver.sh start` | 생성기에 추적 수신기(OTLP/HTTP 4318, `otel/opentelemetry-collector-contrib:0.136.0`) | — (생성기) |
| 2 | `bash node/measure-mode.sh on <생성기 사설 IP>` | 앱 노드 `.env`에 측정 모드 두 줄 · 슬롯 넷을 하나씩 재생성. **배치 스위치 넷이 모든 앱 노드에서 `false`여야 한다** — 아래 | **예** |
| 3 | `bash node/auto-explain.sh on <문턱 ms> --recreate-slots` | 앱 DB에 auto_explain(재기동 없음 · 새 세션부터) · 슬롯 재생성 | **예** |

**측정 모드의 조건 — 배치 스위치 넷.** `PROPERTY_BATCH_REFRESH_ENABLED` · `RISK_BATCH_REGISTRYREFRESH_ENABLED` · `RISK_BATCH_MOCKLEDGERREPLACE_ENABLED` · `BATCH_STARTUPCATCHUP_ENABLED`가 앱 노드 `.env`에서 전부 `false`가 아니면(줄이 없으면 앱 기본값 `true`) `on`은 어느 노드의 `.env`도 바꾸지 않고 거부한다. 측정 모드는 배치 구간도 남겨 기동 한 번이 구간 약 26만 개 · 수신기 파일 1 GB를 냈고(로컬), 요청만으로 슬롯 메모리가 644/680 MiB까지 갔다(로컬). 배치는 슬롯을 OOM으로 죽인 이력이 있다.

**추적 수신기 — 판 고정과 비우기.** `trace-receiver.sh`는 `0.136.0`에 고정한다. `collect.sh`는 수신기 파일을 복사한 뒤 비우는데(옮기지 않는다 — 수신기가 옮긴 파일에 계속 쓴다), 이것은 파일 내보내기의 `append: true`(`otelcol.yaml`)에 기댄다. 그 판에서 로컬로 확인했다 — 비운 뒤의 쓰기가 파일 앞부터 다시 쌓이고(빈 바이트 앞머리 없음, 줄 유실 · 중복 없음), `append: false`면 앞이 빈 바이트로 채워진다. 판을 올리면 이 확인을 다시 한다.

2와 3을 이어서 하면 슬롯 재생성을 한 번으로 줄일 수 있다 — 2를 `--no-recreate`로, 3을 `--recreate-slots`로. 재생성은 부하가 없을 때만 한다. 앱 노드 → 생성기 4318은 보안 그룹이 열어야 한다(운영 작업 — 이 스크립트들은 열지 않는다).

## 회차마다

| 순서 | 명령 | 하는 일 | 운영 작업 |
| --- | --- | --- | --- |
| 1 | `bash node/pre-round.sh <회차>` | 행수 · 가시성 맵 · 적중률 · 자동 청소 · 복제 · 슬롯 넷 · 배치 스위치 · 크레딧 · Redis 명령 통계 기록 → **질의 통계 초기화**(`--no-reset`이면 건너뜀). 경고가 나오면 시작할지 정한다 | 질의 통계 초기화만(통계 누적값 — 서비스와 무관) |
| 2 | `bash node/record.sh start <회차>` | 노드 표본기(지표 5초 · DB 대기 종류 1초 · nginx 접근 로그) · 생성기 vmstat · sar | — |
| 3 | JMeter 실행 | 결과를 `results/<회차>/jmeter/result.jtl`에 | — |
| 4 | `bash node/record.sh stop <회차>` | 표본기 멈춤 · 가져오기 · Redis 명령 통계 · 크레딧 | — |
| 5 | (10초 이상 뒤) `bash node/collect.sh <회차>` — 조건을 바꾼 회차면 `--heavy` | 질의 통계 상위 20 · 전체(`db/<노드>-pgss-all.csv.gz`, 첫 열 `datname`) · auto_explain 구간 · 추적 파일 · (`--heavy`) 설정 · 인덱스 · 테이블 통계 | — |
| 6 | `python -m analyze <회차>` | 집계 · 그래프 · 보고서 — 집계 쪽 문서를 따른다 | — |
| 선택 | `bash node/profiler.sh <app01\|app02> <app-1\|app-2> <cpu\|alloc> <초> <회차>` | 회차 도중 슬롯 하나에 async-profiler. APP-01 여유 메모리 300 MiB 미만이면 거부 | **예**(도는 JVM에 붙는다) |
| 정리 | `bash node/record.sh clean <회차>` | 노드 `/tmp/rental-measure/<회차>` 지움 — 4가 다 가져온 것을 본 뒤 | — |

회차 이름은 영문 · 숫자 · `_` · `-`만(예: `R01`, `U03`).

## 시험 끝

| 순서 | 명령 | 운영 작업 |
| --- | --- | --- |
| 1 | `bash node/auto-explain.sh off --recreate-slots` | **예** |
| 2 | `bash node/measure-mode.sh off` | **예** |
| 3 | `bash node/trace-receiver.sh stop` | — (생성기) |

## 결과 폴더

```
results/<회차>/
├── meta.json                       round · start_utc · end_utc(UTC, 밀리초) · interval_s · generator · nodes · node_clock_utc(읽지 못한 노드는 null)
├── pre/   pre.json · <db노드>-db.json · slots.txt · batch-flags.txt · credits.json · redis-commandstats.txt
├── post/  credits.json · redis-commandstats.txt
├── metrics/<노드>.prom.gz          「# SCRAPE <유닉스 초> <대상>」 줄 + 그 대상의 노출 형식 본문(다 받은 것만), 실패면 「# SCRAPE_ERROR <대상>」 한 줄, 반복
├── db/    <노드>-wait.csv · <노드>-pgss.csv · <노드>-pgss-all.csv.gz(gzip CSV, 첫 열 datname) · <노드>-dbstats.csv · <노드>-auto-explain.log
├── nginx/access.log
├── gen/   vmstat.txt · sar-dev.txt
├── traces/spans.jsonl
├── heavy/ <노드>-settings.csv · -indexes.csv · -tables.csv     (--heavy)
├── profiler/<노드>-<슬롯>-<모드>.collapsed · .html             (선택)
└── jmeter/result.jtl                                          (JMeter)
```

노드 이름은 `app01` · `app02` · `db01` · `db02`다. 각 파일의 열 · 형식은 해당 스크립트 머리 주석이 정본이다.
