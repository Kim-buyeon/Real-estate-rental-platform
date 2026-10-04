# 양식 칸 × 모니터링 요소 대조표 — INF-06 #390

결과 양식 5판(`chaos-harness/measure/report/blank-form.html`)의 **채우는 칸마다** 무엇으로 채우는지, 그리고 관측 설계서 3장의 **요소마다** 어느 칸에 쓰이는지를 한 장에 둔다. 승인된 계획의 「계획의 기준 — 양식 전 칸 × 모니터링 요소 대조표를 먼저, 회차를 맞춘다」가 이 문서다. 계산 규칙은 `chaos-harness/measure/analyze/README.md`, 수집 형식은 각 스크립트 머리 주석이 정본이다 — 여기서는 잇기만 한다.

## 이번 회차 구성 (계획 변경 3 반영)

범위는 엔드포인트 20개 — 양식 [1.4] 의 9개 + 조회 4 · reissue · 쓰기 6. 워밍업 30초. 시간 제약으로 회차를 줄였다 — [1.3] 「회차 길이」 줄(자동)과 `narrative.comparability["회차 길이"].impact` 에 적는다.

| 회차 | 무엇 | 주로 채우는 칸 |
| --- | --- | --- |
| R01 · R02 · R03 | 20개 혼합, 고정 부하 3분 — 흔들림 | [0.2](3) 유의차 하한 · [1.4] 실측 p95 · [4] [5] [6] 「목표 부하」 칸 · [5.6](가) · [7.11](1) |
| U01 ~ U09 | 양식 [1.4] 의 9개를 하나씩 단독, 짧은 계단(4단계 × 90초) | [1.4] 단독 한계 · [7.11](2) 단가 · [7.2] · [7.5](3) 엔드포인트별 |
| U10 ~ U20 | 나머지 11개를 하나씩 단독, 고정 부하 2분(계단 없음 — 포화점 판정 대상 아님) | [7.11](2) 단가 · [5.3] · [7.1]. [1.4] 단독 한계는 「미측정 — 고정 부하라 한계를 찾지 않는다」 |
| R04 | 20개 혼합 계단(S8 한계 탐색, 단계당 2분) | [0.4](2) 포화점 · [7.2] · [7.4] 한계 시점 · [7.5](1)(3)(6) · [7.7] · [7.8] |
| R05 | 로그인 burst(S4) | [7.5](1) S4 · [6.2] 로그인 몰림 · [7.7] 「로그인이 몰린다」 · [7.8] |
| P01 | 혼합 + 프로파일러(비교 회차에 섞지 않는다) | [5.2] |
| E01 | 엔드포인트 SQL 전부 실제 값 EXPLAIN(`queries/login.yaml` · `queries/endpoints.yaml`) | [4.2] [4.3] [4.4] [4.7] [6.1] · S4 · S5 |

R06 · R07 ~ R10 은 없다(계획 변경 1 · 2). 그 회차로 채우려던 칸은 아래에서 「미측정 — 이번 회차 구성에서 뺌(계획 변경 1)」이다.

**엔드포인트 행 · 열** — [1.4] · [5.3] · [7.11](1)(2) · [8] 은 템플릿이 summary 의 엔드포인트(레이블 · 경로)로 행 · 열을 만든다. 양식 표에 없는 11개는 빈 행에 이어 붙는다. 레이블 꼬리 ` setup` 은 통계에서 빼고, ` bg`(배경 부하)는 같은 경로로 합친다(엔드포인트 키는 URL 로 정한다). wrong · enum 레이블의 401 은 JMeter 가 성공으로 친다 — 오류율에 들어가지 않고 5xx 는 상태 코드로 센다.

## 읽는 법

- **원천** — 관측 설계서 3장 번호(W · O · D · S · X), 그 밖은 jtl(부하 도구) · 추적(6장 · 측정 모드) · 프로파일 · EXPLAIN · 알림 이력 · 질의 파일.
- **수집** — `node/` 스크립트(`record.sh`: 지표 · D6 대기 · Nginx 로그 / `collect.sh`: 질의 통계 · auto_explain · 추적 · `--heavy` / `pre-round.sh` / `credits.sh` / `profiler.sh` / `explain.sh` / `alerts.sh`), JMeter 쪽 `run.sh`(jtl · `steps.json` · `round.json`).
- **summary 키** — `out/summary.json` 의 경로(`resources.nodes.<노드>.cpu_pct` 처럼). 「—」는 집계에 없는 것.
- **채움** — 자동(템플릿이 summary 로) · 수동(사람이 `results/narrative.json` 또는 보고서에) · 고정(양식 문구 — 채우는 칸 없음).
- **미측정 — 사유** — 이번 시험에서 값을 내지 않는 칸. 보고서에는 「미측정」으로 남긴다.

## 1. 양식 칸 → 원천

### 표지 · [0]

| 칸 | 원천 | 수집 | summary 키 | 채움 | 회차 |
| --- | --- | --- | --- | --- | --- |
| 표지 판 · 작성자 · 관련 이슈 | — | — | `narrative.cover` | 수동 | — |
| 표지 작성일 · 측정 기간 · 회차 범위 | 회차 폴더 | — | `window` (모든 회차) | 자동 | 전 회차 |
| 표지 기준 회차 · 유의차 하한 | 흔들림 | — | `significance.json` | 자동 | R01 ~ R03 |
| 표지 데이터 규모 · 부하 도구 | `round.json` | `run.sh` | `meta.data_scale` · `meta.tool` | 자동(값은 사람이 `round.json` 에) | 전 회차 |
| [0] 원칙 · [0.1] 계층 귀속 | — | — | — | 고정 | — |
| [0.2](2) 기준 회차 · 선정 사유 | — | — | `narrative.baseline_round` · `baseline_reason` | 수동 | R01 ~ R03 중 |
| [0.2](3) p95 · 달성 TPS · DB CPU · 오류율 × 1 ~ 3회 · 중위수 · 범위 · 편차 (4줄) | jtl · O1(DB) | `run.sh` · `record.sh` | `significance.json` ← `key.p95` · `tps` · `db_cpu` · `error_rate` | 자동(`python -m analyze jitter R01 R02 R03`) | R01 ~ R03 |
| [0.2](3) 유의차 하한 | 위 | — | `significance.json.threshold_pct` | 자동 | R01 ~ R03 |
| [0.2] 서버 지표 대조(안내 문구) | W1 | `record.sh` | `resources.server_latency.<uri>.p95_ms` → [7.1] 「서버 지표 p95 (W1)」 줄 | 자동 | 전 회차 |
| [0.3] 측정 요소 표 (29줄) | — | — | — | 고정(요소 목록) — 실제 수신은 아래 반영 확인 | — |
| [0.3] 반영 확인 — 추적 · D6 · D8 ≠ 0 · W7 · 크레딧 · 생성기 · 프로파일 · auto_explain (8줄 × 최근 6회차) | 추적 · D6 · D8 · W7 · O15 · 생성기 vmstat · 프로파일 · auto_explain | `collect.sh` · `record.sh` · `credits.sh` · `profiler.sh` | `checks.*` | 자동 | 최근 6회차 |
| [0.3] 측정 수단 — 추적 · 프로파일러 · auto_explain | `round.json` | `run.sh` | `meta.tracing_ratio` · `checks.profiler` · `meta.auto_explain_ms` | 자동 | 전 회차 |
| [0.4] 백분위 뜻 · (1) 근거 표 · (3) 분모 표 · (4) 모양 표 · 정리 표 | — | — | — | 고정 | — |
| [0.4](2) p95 500 ms 초과 · 5xx 1% 초과 · 정체 (3줄) | jtl | `run.sh` | `jtl.saturation` · 계단이면 `steps.steps[].saturation_conditions` | 자동(보조 판정) | R04 · R05 · U01 ~ U09 (흔들림 회차는 「아님」) |
| [0.4](2) 단계 표 · 포화점 문장 | jtl · `steps.json` | `run.sh` | `steps.steps` · `steps.saturation` | 자동 | R04 · U01 ~ U09 |
| [0.4](2) 엔드포인트별 처음 넘은 단계 | jtl · 추적 | `run.sh` · `collect.sh` | `steps.route_saturation` | 자동 | R04 |
| [0.4](5) 회차당 요청 수 · 쓸 수 있는 꼬리 | jtl | `run.sh` | `jtl.requests` · `jtl.usable_tail` | 자동 | 전 회차 |

### [1] 한 장 요약

| 칸 | 원천 | 수집 | summary 키 | 채움 | 회차 |
| --- | --- | --- | --- | --- | --- |
| [1.1] 결론 1 · 2 · 3 | [7.11] · [7.4] · [9.1] | — | `narrative.conclusions` | 수동 | R04 · U* 를 본 뒤 |
| [1.1] 2번 「이번 최적화로 한계가 … 올라갔다」 | — | — | — | 미측정 — 이번 시험에 최적화가 없다(시험에서 드러난 문제 고치기는 범위 밖, 별도 이슈) | — |
| [1.1] 측정 줄 | 위 칸들 | — | `rids` · `thr` · `narrative.headroom` | 자동 + 수동(헤드룸 · 분모) | — |
| [1.2] 대차대조표 10줄 · 계층별 집계 4줄 | — | — | `narrative.ledger` · `contribution` | 미측정 — 이번 시험에 최적화가 없다(범위 밖) | — |
| [1.2] 판정 뜻 표 | — | — | — | 고정 | — |
| [1.3] 데이터 규모 | `round.json` | `run.sh` | `meta.data_scale` | 자동 | 기준 R01 · 비교 R04 · R05 |
| [1.3] plan_cache_mode · random_page_cost · 병렬 워커 상한 | pg_settings | `collect.sh --heavy` → `heavy/<노드>-settings.csv` | — (파일 목록만 `heavy_files`) | 수동(파일을 보고 `narrative.comparability`) | R01 · R04 (`--heavy`) |
| [1.3] 인덱스(마이그레이션) · 커밋 | `round.json` | `run.sh` | `meta.migration` · `meta.commit` | 자동(커밋) · 수동(인덱스) | 전 회차 |
| [1.3] 슬롯 메모리 상한 · 누수 감지 · 읽기 분산 | 운영 Compose · `.env` | — | — | 수동 | — |
| [1.3] 부하 생성기 | `meta.json` | `record.sh` | `meta.generator` | 자동 | 전 회차 |
| [1.3] EBS · CPU 크레딧 (출발) | O15 | `credits.sh`(`pre-round.sh` · `record.sh stop`) | `credits.nodes.*.pre` | 자동 | 전 회차 |
| [1.3] 측정 수단 | `round.json` | — | `meta.tracing_ratio` · `profiler` · `auto_explain_ms` | 자동 | 전 회차 |
| [1.3] 회차 길이(추가 줄) | jtl 통계 구간 | `run.sh` | `window.duration_sec` · `warmup_sec` · 영향은 `narrative.comparability["회차 길이"].impact`(「시간 제약으로 회차 길이 단축」) | 자동 + 수동 | 전 회차 |
| [1.3] 영향 · 판정 | — | — | `narrative.comparability_verdict` | 수동 | — |
| [1.3] 되돌리는 법 표 | — | — | — | 고정 | — |
| [1.4] 엔드포인트 행(양식 9행 + 11개) — 실측 p95 | jtl | `run.sh` | `endpoints[].p95` (레이블별) | 자동 | R01 ~ R03(목표 부하) |
| [1.4] 엔드포인트 행 — 목표 p95 | 시험 계획서 2.3 단일 기준 | — | `narrative.endpoints.<레이블>.target_p95_ms`(없으면 「500 (단일 기준)」) | 수동(엔드포인트별 목표는 미확정) | — |
| [1.4] 엔드포인트 행 — 단독 한계 | jtl · `steps.json` | `run.sh` | U* 회차의 `steps.limit.actual_rps` (`report.solo_limits`) | 자동 | U01 ~ U09 (U10 ~ U20 의 11개는 미측정 — 고정 부하라 한계를 찾지 않는다) |
| [1.4] 엔드포인트 행 — 헤드룸 · 헤드룸 분모 | 단독 한계 ÷ 트래픽 정의서 피크 | — | `narrative.endpoints.<레이블>.headroom` · `headroom_basis` | 수동 | U01 ~ U09 |
| [1.4] 엔드포인트 행 — 상태 · 병목 계층 · 주로 쓰는 자원 | jtl · 추적 · Nginx | `run.sh` · `collect.sh` · `record.sh` | `endpoints[].status` · `bottleneck_layer` · `main_resource`(← `matrix`, 20열) | 자동 | R01 ~ R03 · R04 |
| [1.4] 색 조건 표 | — | — | — | 고정 | — |
| [1.4] notifications 행의 p95 | jtl | `run.sh` | `endpoints` | 자동 — 다만 SSE 는 응답 시간이 연결 시간이라 추적 · [7.1] · [7.11] 에서 뺀다(분석 규칙) | R01 ~ R03 |

### [2] 데이터 적재 · [3] 시나리오

| 칸 | 원천 | 수집 | summary 키 | 채움 | 회차 |
| --- | --- | --- | --- | --- | --- |
| [2.1] 11줄 — 적재 후(현재 행 수 · DB 크기) | D12 표 통계 · pre.json | `pre-round.sh` | `pre.db.db01.tables.*.n_live_tup` · `db_size_bytes` | 수동(값은 pre.json) | R01 |
| [2.1] 적재 전 · 배수 · [2.2] 5줄 · [2.3] 7줄 · [2.4] 6줄 | — | — | — | 미측정 — 이번 시험은 데이터를 적재하지 않는다(지금 규모 그대로, 단계적 증가는 범위 밖) | — |
| [3] S1 정상 | — | — | — | 수동 ■ — R01 ~ R03 의 20개 혼합이 이 모양 | R01 ~ R03 |
| [3] S4 로그인 폭주 | — | — | — | 수동 ■ | R05 |
| [3] S8 한계 탐색 | — | — | — | 수동 ■ | R04 (U* 도 계단) |
| [3] U* 단독 | — | — | — | 수동 ■ | U01 ~ U09 |
| [3] S2 · S3 · S5 · S6 · S7 · S9 | — | — | — | 미측정 — 이번 시험 범위 밖(계획 「범위 밖」) | — |

### [4] DB 계층

| 칸 | 원천 | 수집 | summary 키 | 채움 | 회차 |
| --- | --- | --- | --- | --- | --- |
| [4.1] 1 ~ 5위 — 질의 · 호출수 · 평균 · 총시간 · 점유율 | S1 · S2 | `collect.sh`(`db/<노드>-pgss.csv` · `-pgss-all.csv.gz`) | `db.top_merged[]` | 자동 | R01 ~ R03 · R04 |
| [4.1] 1 ~ 5위 — 추적 p50 · p95(평균 칸 아래) | 추적 SQL 구간 | `collect.sh`(추적 파일) | `db.top_merged[].trace` ← `traces.statements` | 자동 | R01 ~ R03 · R04 |
| [4.1] 1 ~ 5위 — 엔드포인트 | 추적 · 질의 파일 `endpoint` | `collect.sh` · `explain.sh` | `db.top_merged[].routes` · `routes_source` | 자동(추적에 없으면 질의 파일) | R01 ~ R03 · E01 |
| [4.1] 나머지 ___종 | S2 | `collect.sh` | `db.pgss.<노드>.statements` | 자동 | R01 ~ R03 |
| [4.1] 전후 표 — DB 총 실행 시간 · 상위 1종 · 상위 3종 · 요청당 DB 시간 (4줄) | S2 · jtl 요청 수 | `collect.sh` | `db.total_db_ms` · `top_merged` · `db.db_ms_per_request` | 자동 | 전: R01 · 후: R04 (조건이 다르면 [1.3]) |
| [4.2] 질의마다 — 접근 방법 · 조인 · 예상/실제 행 · 추정 오차 · 블록 hit/read · 정렬/임시 · 실행 시간 (7줄 × 9개 엔드포인트 SQL) | S6 실제 값 EXPLAIN | `explain.sh <E01> queries/login.yaml queries/endpoints.yaml` | `explain.queries[].warm` (콜드는 `cold`) | 자동 | E01 |
| [4.2] 질의마다 「전」 열 | — | — | 기준 회차의 `explain` | 미측정 — 이번 시험에 EXPLAIN 회차가 E01 하나(전후 비교 없음) | — |
| [4.2] 질의마다 추적 속도(회차 · 단계별 p95) | 추적 | `collect.sh` | `explain.queries[].trace` · `trace_by_step` | 자동 | R04(단계) · R01 ~ R03 |
| [4.2] 운영 실제 계획 — 실제 계획 건수 / 그중 의도한 계획 | auto_explain | `auto-explain.sh on` · `collect.sh` | `explain.queries[].auto_explain_plans` · `auto_explain_same` · `explain.auto_explain_*` · `db.auto_explain` | 자동 | auto_explain 을 켠 회차(문턱은 확인 ④) + E01 |
| [4.2] 쓰기 질의 | S6 | `explain.sh --allow-write`(운영 작업 — ROLLBACK 뒤 VACUUM) | `explain.queries[].warm` · `skipped` | 자동(확인 ⑤에서 허락하면) | E01 |
| [4.3] 인덱스 7줄 — 작업 · 크기 · 생성 · 효과 · 쓰기 대가 | — | — | — | 미측정 — 이번 시험은 인덱스를 바꾸지 않는다 | — |
| [4.3] 실제 값 EXPLAIN 이 탄 인덱스(참고 줄) | S6 | `explain.sh` | `explain.queries[].warm.indexes` | 자동 | E01 |
| [4.3] 인덱스 총 크기 · 쓰기 경로 p95 | pre.json · jtl | `pre-round.sh` · `run.sh` | `pre.db.db01.index_size_bytes` · `endpoints`(me/wishlist 쓰기) | 수동 — 전후 없음이라 「후」만 | R01 · R01 ~ R03 |
| [4.3] 죽은 인덱스 (idx_scan = 0) | pg_stat_user_indexes | `collect.sh --heavy`(`heavy/<노드>-indexes.csv`) | — | 수동 | R01 (`--heavy`) |
| [4.3] 넣지 않은 인덱스와 사유 | — | — | — | 미측정 — 인덱스 작업이 범위 밖 | — |
| [4.4] 한 번도 분석되지 않은 표 · 자동 청소 마지막 실행 · 바뀐 행 비율(S3) | S3 · D12 | `collect.sh --heavy`(`heavy/<노드>-tables.csv`) · `pre-round.sh` | `pre.db.*.tables.*.last_autovacuum` (나머지는 파일) | 수동 | R01 |
| [4.4] 추정 오차 10배 초과 질의 | S6 | `explain.sh` | `explain.error_over_limit` | 자동 | E01 |
| [4.4] 통계 목표를 올린 열 | — | — | — | 미측정 — 통계 작업이 범위 밖 | — |
| [4.4] S4 통계상 행 수 vs 실제 · S5 컬럼 통계(참고 줄) | S4 · S5 | `explain.sh`(질의 파일 `snapshots`) | `explain.s4` · `explain.s5` | 자동 | E01 |
| [4.5] DB 설정 8줄 — 후 | pg_settings | `collect.sh --heavy` | — | 수동(파일) | R01 |
| [4.5] DB 설정 8줄 — 전 · 근거/효과 | — | — | — | 미측정 — 이번 시험은 DB 설정을 바꾸지 않는다 | — |
| [4.6] 노드 CPU | O1 | `record.sh` | `resources.nodes.db-0x.cpu_pct` | 자동 | R01 ~ R03 · R04 |
| [4.6] iowait · steal | O1 · O3 | `record.sh` | `resources.nodes.db-0x.cpu_modes_pct` | 자동 | 같음 |
| [4.6] 버퍼 적중률 | D7 | `record.sh`(postgres exporter) · `collect.sh`(dbstats 대체) | `resources.postgres.<노드>.hit_ratio_pct` | 자동 | 같음 |
| [4.6] 디스크 읽기 (blk_read_time) | D8 | `record.sh` | `resources.postgres.<노드>.blk_read_time_delta` | 자동 | 같음 |
| [4.6] 디스크 IOPS · 처리량 | O8 | `record.sh` | `resources.nodes.db-0x.disk_iops` · `disk_bps` | 자동 | 같음 |
| [4.6] 임시 파일 | D7 | `record.sh` | `resources.postgres.<노드>.temp_bytes_delta` | 자동 | 같음 |
| [4.6] 활성 커넥션 | D2 | `record.sh` | `resources.postgres.<노드>.connections_by_state_max.active` | 자동 | 같음 |
| [4.6] 커넥션 대기 | W3 | `record.sh` | `resources.app_totals.pools.<pool>.pending_max` | 자동 | 같음 |
| [4.6] 오래 열린 트랜잭션 | D3 | `record.sh` | `resources.postgres.<노드>.max_tx_duration_sec` | 자동 | 같음 |
| [4.6] 락 대기 / 데드락 | D6 · D4 | `record.sh` | `db.wait.<노드>.grouped.Lock` · `resources.postgres.<노드>.deadlocks_delta` | 자동 | 같음 |
| [4.6] 복제 지연 | D10 | `record.sh` | `resources.postgres.db-02.replication_lag_max_sec` | 자동 | 같음 |
| [4.6] WAL 생성량 | D13 | `record.sh` | `resources.postgres.<노드>.wal_bytes_delta` | 자동 | 같음 |
| [4.6] 체크포인트 | D9 | `record.sh` | `resources.postgres.<노드>.checkpoints_delta` | 자동 | 같음 |
| [4.6] 컨테이너 메모리 | cgroup(postgres · postgres-standby) | `record.sh`(표본기 `cloop`) | `resources.postgres.<노드>.container_memory_max_bytes` · `_limit_bytes` ← `containers` | 자동 | R01 ~ R03 · R04 |
| [4.6] 대기 종류 6줄 (CPU · IO · Lock · LWLock · Client · 기타) | D6 | `record.sh`(1초 표본) | `db.wait.<노드>.grouped` | 자동 | 같음 |
| [4.6] 나빠진 것과 이유 | — | — | `narrative.db_worse` | 수동 | — |
| [4.7] 1 ANALYZE · 4 블록 과다 · 7 ~ 11 | — | — | — | 수동 | E01 을 본 뒤 |
| [4.7] 2 추정 · 실제 벌어짐 · 3 인덱스 · 6 디스크 정렬 | S6 | `explain.sh` | `explain.error_over_limit` · `indexes` · `disk_sorts` | 자동 | E01 |
| [4.7] 5 · 9 | — | — | — | 고정(→ [6]) | — |

### [5] 애플리케이션 계층

| 칸 | 원천 | 수집 | summary 키 | 채움 | 회차 |
| --- | --- | --- | --- | --- | --- |
| [5.1] 앱 노드 CPU | O1 | `record.sh` | `resources.nodes.app-0x.cpu_pct` | 자동 | R01 ~ R03 · R04 |
| [5.1] 앱 노드 steal · load÷vCPU | O3 · O2 | `record.sh` | `cpu_modes_pct.steal` · `load_per_vcpu` | 자동 | 같음 |
| [5.1] 요청 처리 스레드 · 스레드 큐 대기 | W4 | `record.sh` | `resources.app_totals.tomcat_busy_max` · `tomcat_queue_suspected` | 자동 | 같음 |
| [5.1] 힙 · 힙 밖 · GC 일시정지 · GC 횟수 | W5 | `record.sh` | `app_totals.heap_used_max_bytes` · `nonheap_used_max_bytes` · `gc_*` | 자동 | 같음 |
| [5.1] 컨테이너 메모리 (최대 / 상한) | cgroup(app-1 · app-2) | `record.sh`(표본기 `cloop`) | `slots.*.container_memory_max_bytes` · `_limit_bytes` · `_max_pct` ← `containers` | 자동 | R01 ~ R03 · R04 · R05 |
| [5.1] 활성 스레드 수 · 열린 파일 | W5(JVM) · process | `record.sh` | `app_totals.threads_live_max` · `open_files_max` | 자동 | 같음 |
| [5.1] 비동기 · 스케줄러 큐 (W6) | W6 | `record.sh` | `slots.*.executor_queued_max` · `executor_active_max` | 자동 | 같음 (배치는 꺼 두므로 0 이 정상) |
| [5.1] 서버 쪽 TPS ÷ 도구 쪽 TPS | W1 · jtl | `record.sh` · `run.sh` | `app_totals.server_over_tool` | 자동 | 같음 |
| [5.1] 슬롯별 4줄 — 요청 비중 · p95(서버) · 힙 최대(+ 컨테이너 메모리) · 재시작(+ 상한 OOM) | W2 · 추적 · W5 · cgroup | `record.sh` · `collect.sh` | `slots.*.request_share_pct` · `traces.slots` · `heap_used` · `container_memory_*` · `restarts` · `container_oom_kills` | 자동 | 같음 |
| [5.1] 컨테이너 상한 도달 · 노드 OOM | cgroup oom_kill · W2(재시작) · O5 | `record.sh` | `app_totals.container_oom_kills` · `restarts` · `nodes.*.oom_kills_delta` | 자동 | 같음 · R05 |
| [5.2] 비밀번호 해싱 (BCrypt) | 프로파일 · 추적 BCrypt 구간 | `profiler.sh` · `collect.sh` | `profiler.*.categories_pct.bcrypt` · (추적) `steps.steps[].components.bcrypt` | 자동 | P01 (추적 쪽은 R05 · U07) |
| [5.2] 커넥션 누수 감지 · JIT · 코드값 변환 · 직렬화 | 프로파일 | `profiler.sh` | `profiler.*.categories_pct.*` | 자동 | P01 |
| [5.2] 전 / 후 | — | — | — | 미측정 — 프로파일 회차가 P01 하나 | — |
| [5.2] 비고 · 신뢰 조건(기동 후 분) | — | — | `narrative.profile_notes` · `profile_uptime_min` (표본 · JIT 는 자동) | 수동 | P01 |
| [5.3] 엔드포인트 행(양식 5줄 + 20개 전부) — 요청당 질의 · 같은 행 반복 | 추적 | `collect.sh` | `traces.routes.<경로>.sql_per_request` · `repeated_statements` · `per_row_methods` | 자동 | R01 ~ R03 · U01 ~ U20 |
| [5.3] E01 질의(엔드포인트 칸 아래 참고) | 질의 파일 `endpoint` | `explain.sh` | `explain.queries[].endpoint` · `trace.per_request` | 자동 | E01 |
| [5.3] 원인 · 전/후 | — | — | `narrative.n_plus_one` | 수동(전/후는 미측정 — 최적화 없음) | — |
| [5.4] 적중률 · 축출 · 캐시 메모리 · Redis 연결 · lettuce 지연 | W9 | `record.sh` | `resources.redis.*` · `app_totals.lettuce_mean_ms` | 자동 | R01 ~ R03 · R04 |
| [5.4] 빗나감 1회 비용 | — | — | `narrative.cache.miss_cost` | 미측정 — S5 캐시 콜드 회차가 없다 | — |
| [5.4] TTL · 무효화 방식 | 소스 | — | `narrative.cache.ttl` | 수동 | — |
| [5.5] 등기 조회 API · 대장 조회 API — 호출 · p95 · 실패율 | 추적 외부 구간 | `collect.sh` | `traces.external` | 자동 — 시험 중 배치를 끄므로 비면 「미측정 — 9개 엔드포인트 경로에 외부 호출이 없다(조회는 저장된 판정을 읽는다 — 예비 회차에서 확인)」 | R01 ~ R03 |
| [5.5] 타임아웃 · 서킷 동작 | 소스 · 앱 설정 | — | — | 수동 | — |
| [5.6](가) 회차 시작 힙 · 스레드 · 커넥션 · 같은 인가 p95 · EBS (5줄 × 4회차) | W5 · D2 · jtl · O15 | `record.sh` · `credits.sh` | `app_totals.heap_start_bytes` · `threads_live_start` · `postgres.*.numbackends_start` · `key.p95` · `key.ebs_start_min` | 자동 | R01 ~ R03 (재시작 없이 연달아) |
| [5.6](나) S9 지속 7줄 | — | — | — | 미측정 — S9 지속 시험은 범위 밖 | — |
| [5.7] 방안 8줄 | — | — | — | 수동 | — |

### [6] 경계

| 칸 | 원천 | 수집 | summary 키 | 채움 | 회차 |
| --- | --- | --- | --- | --- | --- |
| [6.1] 무엇 · DB 쪽 · 결과 | S6 (`(열)::형`) | `explain.sh` | `explain.queries[].warm.casts` · `explain.casts` | 자동 | E01 |
| [6.1] 앱 쪽(바인딩 타입) · 고친 곳 · 읽은 블록/실행 시간 전후 | 매퍼 소스 | — | — | 수동(전후는 미측정 — 고치기는 범위 밖) | — |
| [6.1] 찾은 ___개 | S6 | `explain.sh` | `explain.casts` | 자동 | E01 |
| [6.2] 경계 안 작업 표 | 추적 트랜잭션 빈 시간 | `collect.sh` | `traces.routes.*.txn_gap_ms` | 자동 | R01 ~ R03 · R05 |
| [6.2] 커넥션 점유 시간 · 풀 대기 최대 | 추적 커밋 구간 · W3 | `collect.sh` · `record.sh` | `key.conn_hold_p95_ms` · `key.pool_pending_max` | 자동 | R05 · R04 |
| [6.2] 2차 피해 — 로그인 몰림 중 조회 화면 p95 | jtl 엔드포인트별 단계 | `run.sh` | `steps.steps[].routes.<조회 경로>.p95` | 수동(값은 summary) | R04 (로그인과 조회가 같은 단계에 섞인다) |
| [6.2] 풀 대기가 0을 넘는 로그인 TPS | W3 단계별 | `record.sh` | `steps.steps[].resources.pool_pending_max` × `actual_rps` | 수동(값은 summary) | U07(auth/login 단독) · R05 |
| [6.3] 엔드포인트 표 · 요청당 질의 합계 · DB 총 시간 · 요청 p95 | 추적 · S2 · jtl | `collect.sh` · `run.sh` | `traces.routes` · `db.total_db_ms` · `key.p95` | 자동(전 열은 미측정 — 최적화 없음) | R01 ~ R03 |
| [6.3] 판정 표 | — | — | — | 고정 | — |
| [6.4] 기본 · 읽기용 풀(슬롯당 × 수) · 최악 합계 · DB max_connections | W3 · D2 | `record.sh` | `app_totals.pools.*.max_per_slot` · `slots` · `postgres.*.max_connections` | 자동 | R01 |
| [6.4] 커넥션 획득 타임아웃 | application.yml | — | `narrative.pool.connection_timeout` | 수동 | — |
| [6.4] 관측 — 풀 대기 · 획득 p95 · 획득 실패 | W3 | `record.sh` | `app_totals.pools.*.pending_max` · `acquire_p95_ms` · `timeouts` | 자동 | R04 · R05 |
| [6.4] DB 커넥션 거절 | — | — | `narrative.pool.db_refused` | 미측정 — DB 로그의 거절 줄을 거두지 않는다(`collect.sh` 는 auto_explain 덩어리만) | — |
| [6.4] DB 쪽 연결 상태 (D2) | D2 | `record.sh` | `postgres.*.connections_by_state_max` | 자동 | R04 · R05 |
| [6.4] 판정 규칙 표 | — | — | — | 고정 | — |
| [6.5] 캐시 무효화 책임 표 | 소스 | — | — | 수동 | — |
| [6.6] properties(반경) · (목록) · notifications — 응답 크기 | Nginx 바이트 | `record.sh` | `nginx.routes.*.bytes_mean` · `bytes_p95` | 자동 | R01 ~ R03 |
| [6.6] 방식 · 문제 | 소스 | — | `narrative.pagination` | 수동 | — |
| [6.6] 행 수 | — | — | — | 미측정 — 응답 행 수 원천이 없다(바이트만) | — |
| [6.7] 분산 대상 · 분산하지 않은 것 · 읽기용 풀 | 소스 · W3 | — | `narrative.read_routing` · `app_totals.pools.replica` | 수동 · 자동(풀) | — |
| [6.7] 분산 대상 트래픽 비중 | 추적 DB 노드 | `collect.sh` | `traces.db_split` | 자동 | R01 ~ R03 |
| [6.7] DB-01 · DB-02 CPU · 읽기용 풀 대기 · 복제 지연 · 조회 충돌 | O1 · W3 · D10 · D11 | `record.sh` | `nodes.db-0x.cpu_pct` · `pools.replica.pending_max` · `postgres.db-02.replication_lag_max_sec` · `conflicts_delta` | 자동 | R01 ~ R03 · R04 |
| [6.7] 오래된 데이터를 본 사례 | — | — | `narrative.read_routing.stale` | 미측정 — 정합성 검사 수단이 없다 | — |
| [6.8] 10줄 확인 · 발견 · 고침 | — | — | — | 수동 | — |

### [7] 통합

| 칸 | 원천 | 수집 | summary 키 | 채움 | 회차 |
| --- | --- | --- | --- | --- | --- |
| [7.1] 엔드포인트 × 구간 — Service 메서드 · 질의 실행 · 추가 질의 · Redis · 외부 · 기타 구간 | 추적(측정 모드 메서드 구간) | `collect.sh` | `budget.<경로>.rows` | 자동 | R01 ~ R03 · U01 ~ U09 |
| [7.1] Nginx · 네트워크 (도구 − urt) · 슬롯 연결 (uct) · 큐 대기 (urt − 서버) | jtl · W7 | `run.sh` · `record.sh` | `budget.<경로>.extra` | 자동 | 같음 |
| [7.1] 커넥션 획득 대기 | W3 | `record.sh` | `budget.<경로>.extra`(전 경로 풀 지표) | 자동(합 밖) | 같음 |
| [7.1] Controller | — | — | — | 미측정 — 컨트롤러 구간이 계측되지 않는다(관측 설계서 6장). 「설명되지 않은 시간」에 들어간다 | — |
| [7.1] 직렬화 · 응답 전송 | — | — | `budget.<경로>.unexplained_ms` | 자동(설명되지 않은 시간에 포함 — 따로는 미측정) | 같음 |
| [7.1] 합계 · 요청 전체 p95 · 설명되지 않은 시간 | 추적 · jtl | — | `budget.<경로>.server_ms` · `tool_p95_ms` · `unexplained_pct` | 자동 | 같음 |
| [7.1] 서버 지표 p95 (W1) — 대조용(추가 줄) | W1 | `record.sh` | `resources.server_latency.*` (`route` 로 잇는다) | 자동 | 같음 |
| [7.1] 세 겹 뺄셈 표 | — | — | — | 고정 | — |
| [7.1] 가장 느린 요청 3건 | 추적 | `collect.sh` | `traces.slowest` | 자동 | R04 · R05 |
| [7.2] 목표 · 목표 × 2 · 한계 지점 · 한계 초과 — 후 | jtl · `steps.json` · O1 · W4 · W3 · 추적 | `run.sh` · `record.sh` · `collect.sh` | `steps.levels[]` | 자동 | R04 (엔드포인트별은 U01 ~ U09 각자) |
| [7.2] 전 열 | — | — | — | 미측정 — 이번 시험이 첫 계단 측정(전후 비교 없음) | — |
| [7.2] 해석 | — | — | — | 수동 | — |
| [7.3] 최적화 5줄 | — | — | — | 미측정 — 이번 시험에 최적화가 없다(범위 밖) | — |
| [7.3] 추적 켜기 (측정) 대가 | — | — | — | 미측정 — 이번 회차 구성에서 뺌(계획 변경 1) | — |
| [7.4] OS 13줄(앱 CPU · steal · load · 실행 대기 · IO 막힘 · 가용 메모리 · 주요 페이지 폴트 · DB CPU · iowait · IOPS · 요청당 대기 · 처리량 · 네트워크 · 오류 · 재전송 · TCP 넘침 · TIME_WAIT · ESTAB · conntrack · OOM) | O1 · O2 · O3 · O4 · O5 · O8 · O9 · O10 · O11 | `record.sh` | `resources.nodes.*` · `headroom` | 자동 | R04 (한계 시점) · R01 ~ R03 (목표 부하) |
| [7.4] EBS 버스트 크레딧 · CPU 크레딧 | O15 | `credits.sh` | `credits.*` | 자동 | 전 회차 |
| [7.4] 앱 — 요청 처리 스레드 · 힙 | W4 · W5 | `record.sh` | `app_totals.*` | 자동 | R04 |
| [7.4] 앱 — 슬롯 컨테이너 메모리 | cgroup | `record.sh`(`cloop`) | `slots.*.container_memory_*` (상한 = memory.max) | 자동 | R04 |
| [7.4] 경계 — 기본 · 읽기용 풀 | W3 | `record.sh` | `app_totals.pools.*` | 자동 | R04 |
| [7.4] DB — 최대 커넥션 | D2 | `record.sh` | `postgres.*.numbackends_max` · `max_connections` | 자동 | R04 |
| [7.4] DB — 컨테이너 메모리 · 입구 — 앞단 컨테이너 메모리 | cgroup(postgres · nginx) | `record.sh`(`cloop`) | `postgres.*.container_memory_*` · `resources.ingress_container` | 자동 | R04 |
| [7.4] 입구 — 앞단 요청 상한 | — | — | — | 미측정 — Nginx 의 상한 거절(429) 수를 내는 지표가 없다(stub_status 에 없다 — W8). Nginx 로그 상태 코드로는 본다 | — |
| [7.4] 캐시 메모리 | W9 | `record.sh` | `resources.redis.memory_*` | 자동 | R04 |
| [7.4] 부하 생성기 CPU | 생성기 vmstat | `record.sh` | `loadgen.cpu_max_pct` | 자동 | 전 회차 |
| [7.4] 헤드룸 20% 미만 자원 | 위 | — | `report.headroom_rows` → tight | 자동 | R04 |
| [7.5](1) S1 정상 — 한계 · 헤드룸 · 먼저 닿는 자원 | jtl · `steps.json` · 위 자원 | `run.sh` | `steps.limit` · `steps.levels[2]` | 수동(값은 summary) | R04 (20개 혼합 = 정상 모양) |
| [7.5](1) S4 로그인 폭주 | 같음 | `run.sh` | R05 의 `steps` 또는 `jtl` | 수동(값은 summary) | R05 |
| [7.5](1) S2 · S3 · S5 · S6 | — | — | — | 미측정 — 이번 시험 범위 밖 | — |
| [7.5](1) 한계 전 열 | — | — | — | 미측정 — 첫 측정(전후 없음) | — |
| [7.5](1) 대표 헤드룸 | 위 | — | `narrative.headroom` · `headroom_basis` | 수동 — 두 시나리오(S1 · S4)만 재므로 「확정 아님」으로 적는다 | — |
| [7.5](2) 측정 당시 · 현재 규모 · 같은가 | `round.json` · pre.json | `pre-round.sh` | `meta.data_scale` | 수동 | R04 |
| [7.5](3) 한계의 50% · 80% · 한계 지점 p95 | jtl · `steps.json` | `run.sh` | `steps.p95_at[]` | 자동 | R04 · U01 ~ U09 |
| [7.5](3) 권고 운영 상한 | 대표 한계 | — | — | 수동 | — |
| [7.5](4) 처리량 헤드룸 · 가장 빡빡한 자원 · 먼저 닿는 것 | [7.4] · [7.5](1) | — | `headroom` · `steps.levels[2]` | 수동 | R04 |
| [7.5](5) 용량 환산 5줄 | 트래픽 정의서 가정 | — | — | 수동 | — |
| [7.5](6) 실측 한계 · 붕괴 시작 · 간격 | jtl · W2(재시작) | `run.sh` · `record.sh` | `steps.limit` · `steps.collapse` · `steps.collapse_gap_rps` | 자동 | R04 · R05 |
| [7.6] 1순위 · 2순위 · 하지 말 것 | [7.4] [7.5] | — | — | 수동 | — |
| [7.6] 증설 권고 표 | — | — | — | 고정 | — |
| [7.7] DB 질의 느려짐 5줄 · 디스크 크레딧 · 응답 커짐 3줄 · 연결 몰림 2줄 — 관측 | 추적 · W3 · W4 · D6 · O15 · W2 · O10 · O11 | 위 수집 전부 | `steps.steps[]` (단계별 자원 · 구성 요소) | 수동(재료는 summary) | R04 |
| [7.7] 로그인 몰림 3줄 — 앱 CPU · 커넥션 점유 · 조회 화면 | O1 · 추적 · jtl | 같음 | R05 `steps` · R04 `steps.steps[].routes` | 수동(재료는 summary) | R05 · R04 |
| [7.7] 캐시가 비워진다 2줄 | — | — | — | 미측정 — S5 캐시 콜드 회차가 없다 | — |
| [7.8] 한계 초과 시 동작 · 무너진 계층 | jtl · 위 | — | `steps` 마지막 단계 | 수동 | R04 · R05 |
| [7.8] 빠른 거절(429 · IP 상한)로 degrade 하는가 | — | — | — | 미측정 — 이번 회차 구성에서 뺌(계획 변경 1) | — |
| [7.8] 프로세스 종료 — 앱 · DB · OS | W2 · 슬롯 상한 OOM(cgroup) · DB 재시작 · O5 · O13 · O14 | `record.sh` | `app_totals.restarts` · `container_oom_kills` · `postgres.*.restarts` · `nodes.*.oom_kills_delta` · `boot` · `systemd_failed` · `fs_readonly` | 자동 | R04 · R05 |
| [7.8] 자동 회복 · 소요 | jtl 꼬리 · 지표 | `record.sh`(JMeter 뒤까지 기록) | — | 수동 — JMeter 가 끝난 뒤 기록을 몇 분 더 이어야 보인다(예비 회차에서 길이를 정한다) | R04 · R05 |
| [7.8] 데이터 유실 | — | — | — | 수동 | — |
| [7.8] 알림이 울렸나 (규칙 · 분 뒤) | 알림 이력(관측 설계서 5.1) | `alerts.sh` | `alerts.fired` · `alerts.rules_compare` | 자동 | R04 · R05 |
| [7.8] 판정 | — | — | — | 수동 | — |
| [7.9] 10줄 — 실측 한계 · 제안 | 한계 단계의 자원 값 | — | `steps.steps[<한계>].resources` · `steps.levels[2]` | 수동(값은 summary). 기존 칸은 「미확정」(관측 설계서 5.3) | R04 |
| [7.10] 실측 한계 TPS | jtl · `steps.json` | — | `narrative.limit_tps` (값은 `steps.limit`) | 수동 | R04 |
| [7.10] 나머지 14줄 | 위 | — | `key.*` | 자동(전 열은 미측정 — 최적화 없음, 같은 회차 묶음 안에서 R01 ↔ R04 비교는 할 수 있다) | R01 ~ R03 · R04 |
| [7.11](1) 20열 × DB 시간 · DB 질의 수 · Tomcat 스레드 · 응답 바이트 · Redis 명령 · 커넥션 점유 | 추적 · Nginx | `collect.sh` · `record.sh` | `matrix` (열 20 + 그 외) | 자동 | R01 ~ R03 |
| [7.11](1) 앱 CPU 줄 | — | — | — | 미측정 — 엔드포인트별 CPU 원천이 없다(무부하 기준값이 없어 단독 단가로도 못 낸다) | — |
| [7.11](1) 공통 (필터) 열 | — | — | — | 미측정 — 필터 구간이 계측되지 않는다 | — |
| [7.11](2) 20열 × Redis · DB 시간 · DB 질의 · 응답 바이트 · 힙 할당 | 추적 · Nginx · W5 할당 | `collect.sh` · `record.sh` | U* 회차의 `traces.routes` · `nginx.routes` · `app_totals.alloc_bytes_per_request` (`report.unit_costs`) | 자동 | U01 ~ U20 |
| [7.11](2) 앱 CPU (ms/건) | — | — | — | 미측정 — 무부하 기준 자원값을 재지 않는다(양식 단가식의 빼는 값) | — |
| [7.11](3) 요청당 공통 Redis 명령 | W9 commandstats | `pre-round.sh` · `record.sh stop` | `redis_commandstats.calls_per_request` (참고) | 수동 | R01 ~ R03 |
| [7.11](4) DB 시간 · Redis 명령 — 예상 · 실측 · 차이 | U* 단가 × 혼합 요청 수 | — | `report.interference` | 자동 | U01 ~ U09 + R01 ~ R03 |
| [7.11](4) 앱 CPU · 해석 | — | — | `narrative.interference` | 미측정(앱 CPU) · 수동(해석) | — |

### [8] 엔드포인트별 카드 · [9] · [10] · [11] · [12]

| 칸 | 원천 | 수집 | summary 키 | 채움 | 회차 |
| --- | --- | --- | --- | --- | --- |
| [8] 카드(20개 중 빨강 · 노랑) — 상황 · 병목 · 계층별 요약 · 전/후 · 규모 민감 · 남은 것 · 판정 | — | — | `narrative.cards` | 수동(전/후는 미측정 — 최적화 없음) | R01 ~ R03 · U* · R04 |
| [8] 카드 — 목표/실측 · 자원 점유 · 단가 · 시간 예산 | jtl · 추적 · Nginx | 위 | `endpoints` · `matrix` · `traces.routes` · `budget` | 자동 | 같음 |
| [8] 카드 — 단가의 CPU ms/건 | — | — | — | 미측정 — [7.11](2) 앱 CPU 와 같은 사유 | — |
| [8] 초록 표(20개 중 통과한 것) | jtl · U* | — | `report.signal_rows` | 자동 | R01 ~ R03 · U01 ~ U09 |
| [9.1] [9.2] | — | — | `narrative.bottlenecks` · `no_effect` | 수동([9.2] 는 미측정 — 시도한 최적화가 없다) | — |
| [9.3] 미측정 · 미확인 | 없는 지표 · 빈 반영 확인 | 전부 | `resources.missing_metrics` · `checks` · `narrative.unmeasured` | 자동 + 수동 | 전 회차 |
| [9.4](가) (나) (다) | 아래 2장 · 회차 결과 | — | `narrative.feedback.unused` · `wanted` · `odd` | 수동 | — |
| [9.4](라) 시험에서 본 상태 · 울린 규칙 | 알림 이력 | `alerts.sh` | `alerts.transitions` (`report.alert_rows`) | 자동 | R04 · R05 |
| [9.4](라) 울렸어야 했나 | — | — | `narrative.feedback.alerts[].should` | 수동 | — |
| [10] 설명 표 · 그래프 규칙 | — | — | — | 고정 | — |
| [10] G1 · G1 보조(단계별 구성 요소 · p95) | jtl · `steps.json` · 추적 | `run.sh` · `collect.sh` | `graphs.G1` · `graphs.G1s` | 자동 | R04 · R05 · U* |
| [10] G2 · G4 · G7 | 지표 · D6 · 추적 · Nginx | `record.sh` · `collect.sh` | `graphs.G2` · `G4` · `G7` | 자동 | R04 · R01 ~ R03 |
| [10] G6 | 회차 요약 | — | `graphs.G6` | 자동 | 전 회차 |
| [10] G3 · G5 | — | — | — | 미측정 — 최적화 전후 회차가 없다 | — |
| [10] 읽은 결과 한 줄 (7개) | — | — | `narrative.graph_notes` | 수동 | — |
| [11.1] 회차 기록표(R01 ~ R05 · U01 ~ U20 · P01 · E01) | `round.json` · jtl · O15 | `run.sh` · `credits.sh` | `meta` · `key` · `window` | 자동 | 전 회차 |
| [11.1] 회차를 버려야 하는 경우 7줄 | 생성기 CPU · W2 · O15 · 오류율 | 전부 | `loadgen` · `app_totals.restarts` · `credits` · `key.error_rate` | 수동(재료는 summary) | 전 회차 |
| [11.1] 회차 간 간격 | 계획(5분, 잠정) | — | `narrative.round_interval_min` | 수동 | — |
| [11.2] 초기화 10줄 · 이 문서의 선택 · 워밍업 길이 | 계획(워밍업 60초) · pre.json | `pre-round.sh` | `pre.warnings` · `meta.warmup_sec` · `narrative.cache_mode` | 수동 | 전 회차 |
| [11.2] 회차 전 확인 — 행수 · 인덱스 크기 · 적중률 · 가시성 맵 · EBS · CPU 크레딧 · 슬롯 넷 (7줄 × 4회차) | D12 · D7 · O15 · W2 | `pre-round.sh` · `credits.sh` | `pre.*` · `credits.*` | 자동 | 최근 4회차 |
| [11.3] 고정값 17줄 | `round.json` · 계획 · `--heavy` | `run.sh` · `collect.sh --heavy` | `meta.*` | 수동 · 일부 자동(커밋 · 마이그레이션 · 도구 · 측정 수단) | R01 |
| [11.3] 지표 수집 간격 | 표본기 5초(관측 설계서의 15초가 아니다) | `record.sh` | `meta.interval_s` | 수동 | — |
| [11.4] 원자료 체크 15줄 | 회차 폴더 | 전부 | 폴더 목록 · `heavy_files` | 수동 | 전 회차 |
| [11.4] 단독 회차 — 무부하 기준 자원값 | — | — | — | 미측정 — 무부하 기준값을 재지 않는다 | — |
| [12] 작성 순서 · 묻는 것 · 마지막 점검 | — | — | — | 고정 · 수동 체크 | — |

## 2. 관측 설계서 3장 요소 → 쓰이는 칸

이번 시험에서 거두는데 어느 칸에도 쓰이지 않는 것은 [9.4](가) 후보로 둔다(회차 결과를 본 뒤 확정).

| 요소 | summary 키 | 쓰이는 칸 | 비고 |
| --- | --- | --- | --- |
| W1 응답 지연 · 5xx · 초당 요청 | `resources.server_latency` · `slots.*.server_requests` | [0.2] 대조 · [5.1] 서버 ÷ 도구 · [7.1] W1 줄 | 판정은 jtl |
| W2 슬롯별 분배 | `slots.*.request_share_pct` · `restarts` | [5.1] 슬롯별 · [7.5](6) · [7.8] · [11.1] | |
| W3 커넥션 풀 | `app_totals.pools` · `steps.steps[].resources` | [4.6] · [6.2] · [6.4] · [7.1] · [7.4] · [7.2] | |
| W4 Tomcat 스레드 | `app_totals.tomcat_*` · 단계별 | [5.1] · [7.4] · [7.2] | |
| W5 JVM | `app_totals.heap_*` · `gc_*` · `alloc_*` | [5.1] · [5.6](가) · [7.4] · [7.11](2) | |
| W6 비동기 · 스케줄러 큐 | `slots.*.executor_*` | [5.1] | 배치를 꺼 두므로 0 이 정상 — 값이 늘 0 이면 [9.4](가) 후보 |
| W7 Nginx uct · urt | `nginx.routes` · `budget.*.extra` | [0.3] · [7.1] · 단계별 큐 | |
| W8 Nginx 동시 연결 · 요청 수 | `resources.nginx` | 없음 | **[9.4](가) 후보** — 이번 양식 칸에 쓰이지 않는다(앞단 상한은 [7.4] 미측정) |
| W9 Redis · lettuce | `resources.redis` · `app_totals.lettuce_*` · `redis_commandstats` | [5.4] · [7.4] · [7.11](3) | |
| O1 CPU | `nodes.*.cpu_pct` · `cpu_modes_pct` | [0.2] · [4.6] · [5.1] · [6.7] · [7.4] · [7.2] | |
| O2 load ÷ vCPU · 실행 대기 · IO 막힘 | `nodes.*.load_per_vcpu` · `procs_running` · `procs_blocked` | [5.1] · [7.4] | 실행 대기 · IO 막힘은 #390 에서 더했다 |
| O3 steal | `cpu_modes_pct.steal` | [4.6] · [5.1] · [7.4] | |
| O4 가용 메모리 · 주요 페이지 폴트 | `mem_*` · `pgmajfault_per_sec` | [7.4] | 페이지 폴트는 #390 |
| O5 OOM | `oom_kills_delta` | [5.1] · [7.4] · [7.8] | |
| O6 파일시스템 사용률 · inode · 읽기 전용 | `filesystems` · `fs_*` | [7.8](읽기 전용 전환만) | **사용률 · inode 는 [9.4](가) 후보** — 양식에 디스크 용량 칸이 없다 |
| O7 디스크가 차는 시점 예측 | — | — | 미구현(관측 설계서) → [9.3] |
| O8 디스크 util · 대기 · IOPS · 처리량 | `disk_util_pct_max` · `disk_await_ms` · `disk_iops` · `disk_bps` | [4.6] · [7.4] · G2 | 요청당 대기는 #390 |
| O9 네트워크 · 오류 · 버림 · 재전송 | `net_bps_max` · `net_errors` · `tcp_retrans` | [7.4] 네트워크 줄 | 오류 · 재전송은 #390 |
| O10 TCP 연결 · TIME_WAIT · 넘침 | `tcp_estab` · `time_wait_max` · `listen_overflows_delta` | [7.4] · [7.7] | ESTAB 은 #390 |
| O11 conntrack | `nodes.app-01.conntrack` | [7.4] · [7.7] | |
| O12 시간 동기 | `nodes.*.time_sync` | 없음 | **[9.4](가) 후보** — 값이 어긋나면 [9.4](다)에 적는다 |
| O13 부팅 시각 | `nodes.*.boot` | [7.8] 프로세스 종료(OS) | |
| O14 systemd 실패 유닛 | `nodes.*.systemd_failed` | [7.8] 프로세스 종료 | |
| O15 크레딧 | `credits` | [1.3] · [5.6](가) · [7.4] · [11.1] · [11.2] | 스냅샷 |
| D1 DB-02 수집기 | `resources.postgres.db-02` | DB-02 열 전부 | |
| D2 연결 수(상태별) | `postgres.*.connections_by_state_max` · `numbackends_*` | [4.6] · [5.6](가) · [6.4] · [7.4] | |
| D3 오래 열린 트랜잭션 | `postgres.*.max_tx_duration_sec` | [4.6] | |
| D4 커밋 · 롤백 · 데드락 | `postgres.*.xact_*` · `deadlocks_delta` | [4.6] 락 · 데드락 줄(데드락만) | 커밋 · 롤백 초당은 **[9.4](가) 후보** |
| D5 막힌 세션 | `postgres.*.blocked_sessions_max` | 없음 | **[9.4](가) 후보** — [7.7] 락 경합을 볼 때 재료 |
| D6 대기 종류 | `db.wait` | [0.3] · [4.6] · G2 | |
| D7 버퍼 적중 · 임시 파일 | `hit_ratio_pct` · `temp_*` | [4.6] · [11.2] | |
| D8 블록 읽기 시간 | `blk_read_time_delta` | [0.3] · [4.6] | |
| D9 체크포인트 | `checkpoints_delta` | [4.6] | |
| D10 복제 지연 | `replication_lag_max_sec` | [4.6] · [6.7] | |
| D11 standby 조회 충돌 | `postgres.db-02.conflicts_delta` | [6.7] | |
| D12 죽은 행 · 가시성 맵 | `pre.db.*.tables` | [11.2] · [4.4] | pre.json(회차 전) |
| D13 DB · WAL 크기 · 아카이브 | `wal_bytes_delta` · `pre.db.*.db_size_bytes` | [4.6] WAL · [2.1] | 아카이브 실패는 **[9.4](가) 후보** |
| S1 · S2 질의 통계 | `db.pgss` · `top_merged` | [4.1] · [4.6] 요청당 DB 시간 · [7.10] | |
| S3 통계 신선도 | `heavy/<노드>-tables.csv` | [4.4] | `--heavy` 회차만 |
| S4 통계상 행 수 vs 실제 | `explain.s4` | [4.4] 참고 줄 | E01 질의 파일 `snapshots` |
| S5 컬럼 통계 | `explain.s5` | [4.4] 참고 줄 | 같음 |
| S6 추정 vs 실제 | `explain.queries[].warm.error_factor` | [4.2] · [4.4] · [4.7] | E01 |
| X1 외부 경로 감시 | — | — | 미구현(관측 설계서) → [9.3] |
| 추적(6장 · 측정 모드) | `traces` · `budget` · `matrix` · `steps` | [4.1] · [5.3] · [6.2] · [6.3] · [6.7] · [7.1] · [7.2] · [7.11] | BCrypt 구간은 #390 |
| auto_explain | `db.auto_explain` · `explain.*.auto_explain_*` | [0.3] · [4.2] | 문턱은 확인 ④ |
| 알림 규칙(5.1 의 일곱) | `alerts` | [7.8] · [9.4](라) | |

## 3. 「미측정」 정리

1장 표에서 「미측정 —」으로 적은 행은 **38행**이다(한 행이 여러 줄을 묶은 경우 하나로 셌다. U10 ~ U20 의 단독 한계 포함, 「자동 — 비면 미측정」인 [5.5] 는 뺐다). 사유별로 묶으면(한 사유에 여러 행):

| 사유 | 칸 |
| --- | --- |
| 최적화 · 전후 비교가 범위 밖(시험에서 드러난 문제 고치기는 별도 이슈) | [1.1] 2번 · [1.2] · [4.2] 전 · [4.3] 인덱스 · [4.3] 넣지 않은 인덱스 · [4.4] 통계 목표 · [4.5] 전 · [5.2] 전후 · [7.2] 전 · [7.3] 최적화 · [7.5](1) 전 · [10] G3 · G5 |
| 시나리오 범위 밖(S2 · S3 · S5 · S6 · S7 · S9) | [3] · [5.4] 빗나감 비용 · [5.6](나) · [7.5](1) S2 · S3 · S5 · S6 · [7.7] 캐시 비워짐 |
| 계획 변경 1 로 뺀 회차 | [7.3] 추적 켜기 대가 · [7.8] 빠른 거절 |
| 엔드포인트별 CPU · 무부하 기준값 없음 | [7.11](1) 앱 CPU · [7.11](2) 앱 CPU · [7.11](4) 앱 CPU · [8] CPU ms/건 · [11.4] 무부하 기준 |
| 계측되지 않는 구간(Controller · 필터) | [7.1] Controller · [7.11](1) 공통 |
| 원천 자체가 없다 | [6.4] DB 커넥션 거절 · [6.6] 행 수 · [6.7] 오래된 데이터 · [7.4] 앞단 요청 상한 |
| 고정 부하 단독 회차라 한계를 찾지 않는다(계획 변경 3) | [1.4] 단독 한계 — U10 ~ U20 의 11개 |
| 데이터 적재 없음 | [2.1] 적재 전 ~ [2.4] |
| 그 밖 — 위 표에 「미측정」으로 적은 남은 칸(첫 측정이라 전후 · 비교 열이 빈 것 등) | [5.3] 전/후 · [6.1] 전후 · [6.3] 전 · [7.10] 전 · [8] 전/후 · [1.4] 헤드룸 분모 확정 전 · [7.5](1) 대표 헤드룸 확정 |

**가장 큰 사유는 「최적화 · 전후 비교가 범위 밖」이다** — 이번 시험은 현재 상태의 한계와 병목을 재는 것이고, 고치는 일은 별도 이슈다. 그다음은 엔드포인트별 CPU 원천이 없는 것 — [9.4](나) 「있었으면 했던 요소」 후보다. 컨테이너 메모리는 표본기(cgroup 직접)로 채웠다 — 상시 관측에는 없으므로 [9.4](나) 후보로 남는다.
