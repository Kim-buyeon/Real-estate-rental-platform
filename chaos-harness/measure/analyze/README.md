# analyze — 회차 집계 · 그래프 · 보고서

회차 폴더 하나(`results/<회차>/`)를 읽어 집계 JSON · 그래프 · 결과 보고서(PDF)를 만든다. 수집은 `node/` 스크립트가 하고, 이 패키지는 읽기만 한다.

- Python 3.13, 표준 라이브러리 + `matplotlib` · `jinja2` (`pip install matplotlib jinja2`)
- PDF 는 Microsoft Edge 헤드리스로 만든다(Linux 는 chromium · google-chrome). 없으면 HTML 만 남는다.

## 명령

측정 폴더(`chaos-harness/measure`)에서 실행한다.

| 명령 | 하는 일 |
| --- | --- |
| `python -m analyze <회차>` | 집계 → `results/<회차>/out/summary.json` · `out/graphs/G*.png` |
| `python -m analyze <회차> --baseline <기준 회차>` | 위 + 기준 대비 변화(`summary.json` 의 `comparison`) · G3 |
| `python -m analyze <회차> [--baseline <회차>] --report` | 위 + 보고서 `results/report/report-<날짜>.html` · `.pdf` |
| `python -m analyze jitter R01 R02 R03` | 흔들림 회차들로 유의차 하한 → `results/significance.json`, 표 출력 |
| `python -m analyze fixtures` | 가짜 회차 `results/_fixture_R01` · `_fixture_R02` · `_fixture_W01`(기록이 JMeter 보다 60초 먼저 시작 · 30초 늦게 끝남) · `_fixture_S01`(로그인 계단 4단계 — `steps.json` · BCrypt 구간 · `explain/` · `alerts.json`) 생성(시험용) |
| `python -m analyze.explain split <질의 파일> <폴더>` | 질의 파일을 질의 · 스냅샷 SQL 로 쪼갠다 — `node/explain.sh` 가 부른다(표준 라이브러리만) |
| `python -m unittest discover -s analyze/tests -t .` | 시험 |

가짜 회차(`_fixture*`)는 실제 회차와 섞이지 않는다 — 회차 추세(G6 · [11.1])와 흔들림 파일(`results/_fixture_significance.json`)을 따로 쓴다.

## 입력 — 회차 폴더

없는 파일은 건너뛰고 그 칸은 「미측정」이 된다. 노드 이름은 파일에서 `app01` · `db02`(보고서에서는 `app-01` · `db-02`).

| 파일 | 쓰는 곳 |
| --- | --- |
| `meta.json` | 구간(`start_utc` · `end_utc`), 생성기 |
| `round.json` (측정자가 쓴다) | `scenario` · `changed` · `commit` · `migration` · `warmup_sec` · `notes` · `kind`(mix · solo · jitter). meta 위에 덮는다. 선택 키: `data_scale` · `tool` · `plan` · `command` · `operator` · `tracing_ratio` · `profiler` · `auto_explain_ms` · `target_rps`([7.2] 「목표」 줄 — 없으면 첫 단계) |
| `jmeter/result.jtl` | 응답 시간의 정본이자 **통계 구간의 정본** — 아래 「통계 구간」 |
| `steps.json` (JMeter 실행 스크립트 `chaos-harness/jmeter/run.sh`) | 계단 단계 — `{"start_epoch_ms": <JMeter 시작 직전 ms>, "profile": "<load_profile>", "steps": [{"index": 1, "target_rps": 10.0, "from_s": 0, "to_s": 180}, …]}`, from · to 는 start 기준 초. 없으면 jtl 10초 창 하나를 단계 하나로(목표 없음) — 아래 「단계별 분해」 |
| `explain/` (`node/explain.sh`) | `queries/<파일>.yaml`(질의 파일들의 사본 — `login.yaml` · `endpoints.yaml`, 질의마다 선택 키 `endpoint`) · `<id>.cold.txt`(첫 실행) · `<id>.txt`(세 번째) · `<id>.skipped` · `<id>.error` · `<id>.csv`(스냅샷) · `run.json` — 형식은 그 스크립트 머리 주석 |
| `containers/<노드>.jsonl.gz` (`record.sh stop` 이 가져온다 — 표본기 `node-sampler.sh cloop`) | 5초마다 컨테이너별 cgroup `memory.current` · `memory.max` · `memory.events` oom_kill(Compose 서비스 · id). 반쯤 쓰인 마지막 멤버는 버린다 |
| `alerts.json` (`node/alerts.sh`) | Grafana 알림 상태 이력 봉투 — `history`(`/api/v1/rules/history`) · `annotations` · `rules`(걸린 규칙 목록), 실패한 칸은 null + `<칸>_error` |
| `metrics/<노드>.prom.gz` | 노드 · 슬롯 · Redis · Nginx · PostgreSQL 지표(긁기마다 `# SCRAPE <epoch> <target>`, 실패는 `# SCRAPE_ERROR`) |
| `db/<노드>-wait.csv` · `-pgss.csv` · `-dbstats.csv` · `-auto-explain.log` | 대기 종류(`NONE` 은 표본으로만 센다) · 질의 통계 상위 20 · 회차 전후 적중률 · 실제 계획 |
| `db/<노드>-pgss-all.csv.gz` | 질의 통계 전체(gzip, `datname` + pg_stat_statements 전 열). 앱 DB 행만 합(총 DB 시간 · 점유율 · 요청당 질의)에 쓴다. 앱 DB = ① 상위 20 의 queryid 가 걸리는 DB ② dbstats 의 datname ③ postgres · template 를 뺀 DB 중 총 시간 최대 — 고른 DB 와 근거는 `db.pgss_basis` |
| `nginx/access.log` | 경로별 rt · urt · uct · 바이트, 슬롯 분배(SSE `/api/notifications/stream` 은 지연 통계에서 뺀다) |
| `traces/spans.jsonl` | 요청당 SQL · DB 시간 · 메서드 자기 시간 · store(=Redis) 시간 · 트랜잭션 빈 시간. 깨진 줄은 건너뛴다 |
| `pre/pre.json` · `pre/credits.json` · `post/credits.json` | 회차 전 확인 · 크레딧 |
| `pre|post/redis-commandstats.txt` | Redis 명령 수 전후 차이. 관리 명령(info · config · client · slowlog · latency · memory · dbsize · ping · command · select · cluster · auth · hello — redis_exporter 긁기)은 앱 명령에서 빼고 「측정 수단 몫」으로 따로 |
| `profiler/<노드>-<슬롯>-<모드>.collapsed` | 앱 CPU 분포 · JIT 비중 |
| `gen/vmstat.txt` | 부하 생성기 CPU(첫 데이터 줄 = 부팅 뒤 평균은 버린다) |

## 출력 — `out/summary.json`

| 키 | 내용 | 양식 칸 |
| --- | --- | --- |
| `jtl` | 전체 · 레이블별 p50/p95/p99 · TPS · 오류율 · 5xx · 바이트, 10초 창 시계열, 포화 보조 판정 | [0.4] [1.4] G1 |
| `resources` | 노드 CPU(모드별) · load÷vCPU · 메모리 · 디스크 · TCP · conntrack · OOM, 슬롯별 스레드 · 풀 · 힙 · GC · 할당, PostgreSQL(재시작 포함) · Redis · Nginx, 헤드룸, 없는 지표 목록. 노드마다(#390) `procs_running` · `procs_blocked`(O2) · `pgmajfault_per_sec`(O4) · `filesystems` · `fs_used_pct_max` · `fs_inodes_used_pct_max` · `fs_readonly`(O6) · `disk_await_ms`(O8) · `net_errors` · `tcp_retrans`(O9) · `tcp_estab`(O10) · `time_sync`(O12) · `boot`(O13) · `systemd_failed`(O14). `server_latency` — 경로(uri)별 서버 쪽 p50 · p95 · 5xx(W1, 대조용) | [4.6] [5.1] [6.4] [7.1] [7.4] [7.8] G2 |
| `db` | 대기 종류별 평균 활성 세션 · 질의 통계 상위(행마다 `trace` — 같은 문장의 추적 p50 · p95) · 점유율 · auto_explain 집계 · dbstats | [4.1] [4.2] [4.6] |
| `traces` | 경로별 요청당 SQL · DB 시간 · Redis · 트랜잭션 점유 · 메서드 자기 시간 상위 · 행마다 불리는 메서드 · 반복 문장, 요청 밖 구간, DB 노드별 비중, 가장 느린 3건. `statements` — 문장 키별 호출 · 요청당 횟수 · p50 · p95 · DB 노드 · 부르는 경로 + 질의 통계(`pgss` — 노드별 평균 · 표준편차 · 호출당 행 · 블록) | [4.1] [5.3] [5.5] [6.2] [6.3] [6.7] [7.1] |
| `steps` | 단계마다 목표/실제 RPS · p50/p95/p99 · 5xx · 구성 요소 평균 · 함수 자기 시간 상위 5 · 문장별 p50/p95 · 노드 CPU · 슬롯 자원 · 닿은 포화 조건 · 엔드포인트별(`routes` — 응답 · 구성 요소 · 함수), 회차의 포화점 · 급등 지점 · 한계 · 붕괴 · [7.2] 네 줄 · [7.5](3) 세 점 · 엔드포인트별 처음 넘은 단계(`route_saturation`) | [0.4](2) [7.2] [7.5](3)(6) G1 · G1 보조 |
| `explain` | 질의마다 엔드포인트 · [4.2] 일곱 줄(데운 것 · 콜드) · 형 변환 · 인덱스 · auto_explain 실제/같은 계획 · 추적 속도(회차 · 단계), 스냅샷 S4 · S5 | [4.1] [4.2] [4.3] [4.4] [4.7] [5.3] [6.1] |
| `containers` | 노드 · 서비스별 사용 최대 · 상한 · 비율 · 상한 OOM(id 별 oom_kill 증가 합) · 재시작(id 변화) — `resources.slots.*.container_memory_*` · `postgres.*.container_memory_*` · `ingress_container` 를 채운다 | [4.6] [5.1] [7.4] [7.8] |
| `alerts` | 상태 전환 · 울린 규칙(시작 뒤 분) · 저장소 정의와 걸린 규칙 대조 | [7.8] [9.4](라) |
| `nginx` | 경로별 rt/urt/uct p95 · 바이트, 슬롯별 비중 | [5.1] [6.6] [7.1] |
| `budget` | 경로별 시간 예산 — p95 순위 요청 하나의 분해 · 구성 요소 평균 · 합에 넣지 않는 입구 · 큐 · 풀 줄 | [7.1] G4 |
| `matrix` | 구성 요소 × 경로 점유율 | [7.11] G7 |
| `endpoints` · `key` · `checks` · `comparison` | 신호등 행 · 핵심 지표 · 반영 확인 · 기준 대비 변화 | [1.4] [0.3] [7.10] |

### 통계 구간

JMeter 가 정한다 — 시작 = 첫 표본의 `timeStamp`(5.6.3 기본 `jmeter.properties` 의 `sampleresult.timestamp.start=true` 라 표본 시작 시각), 끝 = 가장 늦은 `timeStamp + elapsed`. 워밍업(`warmup_sec`)은 이 시작부터 세어 뺀다. 지표 · 추적 · Nginx · 대기 종류 · 초당 값(GC s/s · xact/s · 서버 TPS · 축출/s)이 모두 이 구간을 쓴다. 지표 카운터의 증가량은 구간 안 표본의 증가를 구간 길이로 늘린다(Prometheus `increase()` 외삽과 같다 — 대기열 넘침 · OOM 같은 사건 수는 늘리지 않는다). `meta.json` 의 `start_utc` · `end_utc`(record start/stop)는 파일 범위만 정한다. JMeter 결과가 없으면 기록 구간을 쓴다(`window.basis`).

계산 규칙

- 백분위는 선형 보간(numpy 기본 · Excel PERCENTILE.INC 와 같다). p95 는 표본 100 이상, p99 는 1,000 이상일 때만 판정용이고 그 밖은 「참고(표본 부족)」.
- 엔드포인트 키 = 경로에서 `/api/` 를 떼고 숫자 · 경로 변수를 `{id}` 로 바꾼 것. JMeter URL · Nginx 경로 · 추적 `http.route` 가 이 키로 이어진다.
- 메서드 자기 시간 = 구간 길이 − 자식 구간 합집합. Redis 시간 = `.store.` 패키지와 store 밖에서 Redis 를 쓰는 클래스(`DistributedLockAspect` · `StreamTicketStore` · `SseNotificationSender` · `BuildingLedgerDailyQuota` · `BuildingLedgerRateLimiter`, 중첩 클래스 `Outer$Inner` 포함, `code.namespace`)의 메서드 구간 **자기 시간** 합 — RedisTemplate 호출은 계측되지 않아 자기 시간 ≈ Redis 왕복이다. `DistributedLockAspect.lock`(@Around)은 잠긴 업무 메서드 전체를 감싸므로 Redis 가 아니라 보통 메서드로 세고, Redis 호출 수에도 넣지 않는다(acquire · release · Renewal.run 이 Redis 몫). 트랜잭션 = 커밋(롤백) 구간 하나가 닫는 SQL 묶음, 빈 시간 = 그 안에서 SQL 이 없던 시간.
- 행마다 불리는 메서드 = 한 추적(요청) 안에서 100번 넘게 불린 메서드 — 메서드 목록 생성기와 같은 규칙.
- SSE(`/api/notifications/stream`)는 응답 시간이 연결 시간이라 경로 통계 · 시간 예산 · 가장 느린 요청 · [7.11] 에서 빼고 연결 수(`traces.sse_connections`)만 센다.
- 시간 예산([7.1] · G4)은 구간별 p95 를 따로 구해 더하지 않는다. 요청마다 서버 구간을 겹치지 않는 구성 요소(이름 붙은 상위 메서드 자기 시간 · SQL 합집합 · Redis 메서드 자기 시간 · 외부 호출 · 그 밖의 메서드 자기 시간 · 기타 계측 구간 자기 시간)로 나눈 뒤, 구성 요소 평균과 서버 구간이 p95 순위인 실제 요청 하나의 분해를 낸다. 설명되지 않은 시간 = 그 요청의 서버 구간 − 구성 요소 합 = 서버 구간 안 · 계측 구간 밖의 시간(Controller · 필터 · 직렬화 · 계측 안 된 코드). 5% 를 넘으면 계측 공백이다. 락 대기(acquire 의 대기)는 Redis 몫에 들어간다 — 락 경합이 있으면 Redis 왕복이 커 보인다. 입구(도구 − urt) · 슬롯 연결(uct) · 큐(urt − 서버) · 풀 획득 대기(전 경로 지표)는 따로 적고 합에 넣지 않는다.
- 10초 창 시계열의 TPS 는 창이 실제로 덮은 길이로 나누고, 절반도 안 덮은 마지막 창은 정체 판정에서 뺀다.
- 실패한 긁기 — 지금 수집기의 본문 없는 `# SCRAPE_ERROR <대상>` 한 줄은 그 긁기만 빠진 것이라 앞 본문을 그대로 둔다. 예전 모양(머리 · 잘린 본문 · `# SCRAPE_ERROR` 가 한 gzip 멤버)은 그 본문을 통째로 버린다. gzip 은 멤버 단위로 읽는다(반쯤 쓰인 마지막 멤버는 버린다).
- `meta.json` 의 `node_clock_utc` 는 읽지 않는다(값이 null 이어도 된다).
- 질의 통계 점유율 · 총 DB 시간 · 요청당 DB 시간 · 요청당 질의는 `<노드>-pgss-all.csv.gz`(gzip) 의 **앱 DB 행** 합 기준이다. 앱 DB = ① 상위 20 의 queryid 가 가장 많이 걸리는 datname ② dbstats 의 datname ③ postgres · template 를 뺀 DB 중 총 실행 시간 최대 — 고른 DB 와 근거는 `db.pgss_basis`. 전체 파일이 없으면 상위 20 합 기준(점유율이 부풀어 보인다 — `total_basis: top20`).
- 새 OS 요소(#390): O6 은 실제 마운트만 — tmpfs · overlay · proc 같은 가상 파일시스템과 `/run` · `/var/lib/docker` · `/sys` · `/proc` · `/dev` 아래는 뺀다. 사용률 = 1 − 구간 최소 가용 ÷ 크기. O8 요청당 대기 = (읽기 + 쓰기 시간 증가) ÷ (읽기 + 쓰기 완료 증가) — iostat 의 await 와 같은 정의, 물리 장치만. O9 는 물리 NIC(`eth` · `ens` · `enp` · `eno`)만, 재전송 비율 = RetransSegs 증가 ÷ OutSegs 증가. O12 · O13 · O14 는 워밍업 포함 회차 전체 — O14 는 구간에 한 번이라도 `state="failed"` 가 1 이던 유닛(표본기는 노드 exporter 를 직접 긁어 상태 다섯 줄이 다 온다). 지표 이름은 node exporter v1.9.1 의 기본 수집기(stat · vmstat · filesystem · diskstats · netdev · netstat · timex)와 운영 Compose 가 켠 systemd 수집기의 것 — **예비 회차(확인 ④)에서 `missing_metrics` 가 비는지로 실제 수신을 확인한다**(netstat 의 `Tcp_RetransSegs` · `Tcp_OutSegs` · `Tcp_CurrEstab` 는 수집기 기본 필드 목록에 기댄다). DB 재시작은 `pg_postmaster_start_time_seconds` 가 바뀐 횟수 — 없으면 None 이고 없는 지표 목록에 올리지 않는다.
- W1 서버 쪽 지연: 슬롯을 합친 (uri, status)별 누적 버킷 증가로 Prometheus `histogram_quantile` 과 같은 선형 보간. SLO 구간 다섯 칸 사이 값이라 **대조용** — 판정은 jtl. 3 초 칸 위면 값이 없다(`p95_over_3s`).
- 단계별 분해: 단계 구간은 통계 구간(워밍업 뒤 ~ JMeter 끝)으로 자른다 — 워밍업은 첫 단계에서 빠진다. 창의 절반도 못 덮은 단계는 `partial` 로 판정에서 뺀다. 실제 RPS = 단계 안 표본 수 ÷ 단계 길이. 구성 요소 평균은 [7.1] 과 같은 나눔(요청마다 나눈 뒤 평균) — BCrypt = `code.namespace` 가 `BCryptPasswordEncoder` 또는 `AbstractValidatingPasswordEncoder` 인 메서드 구간의 자기 시간(메서드 자기 시간에서 뗀다). 큐 = 단계 Nginx urt 평균 − 서버 구간 평균(Nginx 시각이 초 단위라 단계 끝 1초의 줄은 뺀다), 풀 대기 = 단계 안 획득 시간 합 증가 ÷ 횟수 증가(슬롯 중 최대) — 둘은 합 밖. 노드 CPU 는 비율 점의 간격 가운데가 단계 안인 점만.
- 포화점(보조 판정): 시험 계획서 2.3 의 세 조건 중 먼저 닿은 단계 — p95 > 500 ms · 5xx > 1% · 정체. **정체의 수치는 시험 계획서에 없어 집계 규칙으로 정했다** — 실제 RPS < 목표 × 0.9 이고 앞 단계 대비 실제 증가 < 5%(목표가 없으면 스레드 증가 > 10% 대비 TPS 증가 < 5%, jtl 보조 판정과 같다). 확정은 G1 을 보고 사람이 한다. 급등 지점 = 앞 단계 대비 p95 가 2배 초과인 단계, 그때 평균이 가장 많이 늘어난 구성 요소와 함수. 한계 지점 = 포화 단계 바로 앞(포화가 없으면 마지막 단계 — `reached: false`). 붕괴 시작 = 5xx 또는 슬롯 재시작이 처음 난 단계. [7.2] 목표 = `round.json` 의 `target_rps`(없으면 첫 단계), 목표 × 2 = 목표 RPS 가 그 두 배에 가장 가까운 단계(25% 넘게 어긋나면 「해당 단계 없음」), 「먼저 닿는 자원」 = 그 단계에서 사용률이 가장 높은 것(노드 CPU 평균 · 요청 스레드 최대 · 풀 활성 최대). [7.5](3) = 한계 단계까지에서 실제 RPS 가 한계의 50 · 80% 에 가장 가까운 단계.
- 문장 키: `$n` · `?` · 따옴표 리터럴 · 숫자를 `?` 로, 공백을 한 칸으로, 기호 앞뒤 공백을 지우고 소문자로(최대 1,000자). 질의 통계 · 추적 · 질의 파일 · auto_explain 의 Query Text 가 이 키로 이어진다. 한쪽이 잘렸으면(상위 20 파일은 300자) 앞부분이 80자 이상 겹치는 후보가 **하나뿐일 때만** 잇는다. 추적 문장은 2,000자까지 남긴다.
- [4.2] 일곱 줄: 맨 위 노드의 예상/실제 행 · Buffers(누적), 추정 오차 = 노드마다 max(예상 ÷ 실제, 실제 ÷ 예상) 중 최대(10배 초과 = S6 경고), 정렬 = Sort Method, 임시 = temp read + written, 형 변환 = 조건 줄의 `(열)::형`. auto_explain 대조 = 같은 문장 키의 실제 계획 수와 그중 스캔 노드 (종류, 인덱스|표) 집합이 EXPLAIN 과 같은 수. 스냅샷 CSV 는 열 이름이 `reltuples|estimated` 와 `count|actual` 이면 S4, 아니면 S5.
- 엔드포인트별 단계: 단계 안 표본을 엔드포인트 키(jtl URL · 추적 `http.route` · Nginx 경로의 `route_key`)로 나눠 응답 · 구성 요소를 따로 낸다 — 단독 회차(U*)는 그 하나, 혼합 회차는 엔드포인트마다. 자원 지표는 엔드포인트로 나눌 원천이 없어 단계 전체 값만. 엔드포인트별 처음 넘은 단계는 p95 500 ms · 5xx 1% 두 조건만(엔드포인트별 목표 RPS 가 없어 정체는 회차 전체로만). [1.4] 「단독 한계」는 서술(`narrative.endpoints`)이 없으면 단독 계단 회차(U*, `steps.json`)에서 가장 많이 부른 경로의 한계 단계 실제 RPS(포화 미도달이면 「이상」).
- [4.1] 「엔드포인트」: 추적으로 이은 경로, 추적에 없는 문장은 질의 파일의 `endpoint`(문장 키가 같을 때).
- 알림: 상태 이력의 `Alerting` 전환을 「울렸다」로 센다(Pending 은 대기 중). 「분 뒤」는 통계 구간 시작(JMeter 첫 표본) 기준, 회차가 끝난 뒤의 전환은 `after_end`. 규칙 대조는 `infra/grafana/alerting/rules-*.json` 의 제목.
- 흔들림: 편차 = (최대 − 최소) ÷ 중위수, 하한 = 2 × 네 지표(p95 · TPS · DB CPU · 오류율) 중 최대 편차. 중위수가 0 인 지표는 뺀다.
- 판정(`comparison`): |변화| < 하한이면 「변화없음」, 하한 파일이 없으면 「하한 미정」.

## 보고서

양식 원본은 `report/blank-form.html`, 채우는 양식은 `report/template.html.j2`. 숫자 칸은 `summary.json` 에서 자동으로 채우고, 판단이 필요한 칸은 `results/narrative.json` 에서 온다. 그 파일이 없거나 키가 없으면 칸은 비어 있다(양식의 안내 문구 그대로). 측정해야 하는데 값이 없는 칸은 「미측정」.

그래프 G5 는 `results/contrib.json` 이 있을 때만 그린다 — `{"rounds": "R03 → R09", "items": [{"label": "...", "DB": 40, "앱": 10, "경계": 30, "분리 불가": 20}]}`. G6 의 회차 순서는 `results/rounds.csv`(`round` 열)가 있으면 그것, 없으면 폴더 이름순.

### `results/narrative.json` 키

모든 키가 선택이다.

```json
{
  "cover": {"edition": "5판", "author": "", "issue": "#378"},
  "baseline_round": "R03", "baseline_reason": "",
  "conclusions": ["[1.1] 1번 문장", "2번", "3번"],
  "headroom": 3.2, "headroom_basis": "예상 피크", "headroom_denominator_rps": 40,
  "headroom_by_round": {"R03": "2.1배", "R07": "3.2배"},
  "limit_tps": {"R03": 88, "R07": 140},
  "ledger": [{"layer": "DB", "what": "", "before": "201 ms(R03)", "after": "30.9 ms(R04)", "effect": "", "cost": "", "verdict": "채택"}],
  "contribution": {"DB": 40, "앱": 10, "경계": 30, "분리 불가": 20},
  "comparability": {"plan_cache_mode": {"base": "auto", "cur": "force_custom_plan", "impact": ""},
                    "회차 길이": {"impact": "시간 제약으로 회차 길이 단축"}},
  "comparability_verdict": "■ 조건이 같다",
  "endpoints": {"<JMeter 레이블>": {"target_p95_ms": 500, "single_limit_tps": 120, "headroom": 3.4, "color": "빨강"}},
  "db_worse": "",
  "profile_notes": {"bcrypt": "", "code_conversion": ""}, "profile_uptime_min": 10,
  "n_plus_one": {"<엔드포인트 키>": "원인 · 고칠 곳"},
  "txn_findings": {"<엔드포인트 키>": ""},
  "cache": {"miss_cost": "", "ttl": ""},
  "pool": {"connection_timeout": "5000 ms", "connection_timeout_source": "application.yml", "db_refused": ""},
  "pagination": {"<엔드포인트 키>": "커서"},
  "read_routing": {"targets": "", "excluded": "", "stale": ""},
  "redis_common_per_request": 2, "redis_common_basis": "",
  "interference": {"DB 시간 (ms)": ""},
  "cards": [{"endpoint": "<JMeter 레이블>", "status": "빨강", "situation": "", "bottleneck": "", "db": "", "app": "", "boundary": "",
             "before_after": "", "scale_sensitive": true, "remaining": "", "verdict": "남음"}],
  "bottlenecks": [{"layer": "", "what": "", "impact": "", "why": "", "next": ""}],
  "no_effect": [{"layer": "", "what": "", "expected": "", "measured": "", "conclusion": ""}],
  "unmeasured": [{"layer": "", "item": "", "why": "", "how": ""}],
  "feedback": {"unused": [{"element": "", "cost": "", "why": "", "proposal": ""}],
               "wanted": [{"blocked": "", "needed": "", "cost": ""}],
               "odd": [{"element": "", "seen": "", "expected": "", "cause": ""}],
               "alerts": [{"state": "", "rule": "", "should": ""}]},
  "graph_notes": {"G1": "읽은 결과 한 줄", "G2": ""},
  "round_interval_min": 15, "cache_mode": "warm",
  "data_load": [{"table": "property", "before": "312,661", "after": "998,640", "ratio": "3.2배", "note": ""}],
  "data_gen": [{"what": "실매물", "how": "", "seed": "", "repro": ""}],
  "distribution": [{"item": "자치구별 매물 분포", "basis": "25개 구에 분산", "measured": "", "verdict": "■ 맞음"}],
  "scenarios_run": {"S1": "무엇을 어떤 속도로 돌렸나 — 결과"}
}
```

### 칸을 어디서 채우나

| 양식 칸 | 원천 |
| --- | --- |
| 표지 | 회차 범위 · 기간 · 하한 · 데이터 규모 · 도구는 자동, 판 · 작성자 · 이슈는 서술 |
| [0.2] 흔들림 표 · 하한 | `significance.json` |
| [0.3] 반영 확인 · 측정 수단 | 최근 6회차의 `checks` · `round.json` |
| [0.4](2)(5) | 포화 보조 판정(계단 회차면 단계 표와 포화 단계) · 요청 수 · 쓸 수 있는 꼬리 |
| [4.2] [4.3] [4.4] [4.7] [6.1] | `explain` — 질의마다 일곱 줄 · auto_explain 실제/같은 계획 · 탄 인덱스 · 10배 초과 · 디스크 정렬 · 형 변환 · S4 |
| [7.2] [7.5](3)(6) | `steps` — 계단 회차(`steps.json`)일 때만. 해석 · 권고 운영 상한은 사람 |
| [7.8] 프로세스 종료 · 알림 · [9.4](라) | 슬롯 재시작 · OOM · 재부팅 · systemd 실패 · DB 재시작 · `alerts` — 「울렸어야 했나」는 서술 |
| [1.1] [1.2] [1.3] | 서술(측정 줄 · [1.3]의 데이터 규모 · 커밋 · 크레딧 · 측정 수단은 자동) |
| [1.4] | 레이블별 p95 · 5xx · 병목 계층 · 주 자원 자동, 목표 · 단독 한계 · 헤드룸은 서술. 색은 중심 회차 p95 로 정하되 `endpoints.<레이블>.color` 가 있으면 그것(단독 한계 불합격처럼 중심 회차 밖의 근거) |
| [2.1] [2.2] [2.3] | `data_load` · `data_gen` · `distribution` 이 있으면 그 행, 없으면 빈 양식 |
| [3] 이번에 돌렸나 | `scenarios_run` 의 ID 가 있으면 ■ + 그 글, 없으면 중심 회차의 `scenario` 로 |
| [11.1] 시나리오 · 바꾼 것 · 판정 | 회차 `round.json` 의 `scenario` · `changed` · `verdict`. `verdict` 가 없으면 단독(`kind` 가 `solo` 거나 이름이 U 로 시작)은 「단가」, 나머지는 앞 혼합 회차와 유의차 하한으로 |
| [2] [4.5] [5.7] [6.5] [6.8] [7.3] [7.5](1)(2)(4)(5) [7.6] [7.7] [7.9] [12] · 위에 없는 [4.3] [4.4] [4.7] [7.8] 칸 | 사람 |
| [3] | 이번 회차 시나리오에 표시 |
| [4.1] [4.2] [4.6] | 질의 통계 · auto_explain 건수 · 노드별 자원 · 대기 종류 |
| [5.1] ~ [5.6](가) | 앱 자원 · 슬롯 · 프로파일 · 요청당 질의 · 캐시 · 외부 · 회차 시작값 추이 |
| [6.2] [6.3] [6.4] [6.6] [6.7] | 트랜잭션 빈 시간 · 반복 조회 · 풀 · 응답 크기 · 읽기 분산 |
| [7.1] | 상위 5개 엔드포인트의 시간 예산(전 · 후) · 가장 느린 3건 |
| [7.4] [7.10] [7.11] | 헤드룸 · 전후 한 표 · 점유율(단독 회차 U* 가 있으면 단가 · 간섭) |
| [8] | 카드는 서술, 실측 칸은 자동. 초록 표는 통과한 엔드포인트 자동 |
| [9] | 서술 + [9.3]에 없는 지표 · 빈 원자료 자동 |
| [10] | 그래프 · 읽은 결과는 서술 |
| [11.1] [11.2] [11.3] | 모든 회차 · 회차 전 확인(pre.json · 크레딧) · 고정값 |
