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
| `python -m analyze fixtures` | 가짜 회차 `results/_fixture_R01` · `_fixture_R02` 생성(시험용) |
| `python -m unittest discover -s analyze/tests -t .` | 시험 |

가짜 회차(`_fixture*`)는 실제 회차와 섞이지 않는다 — 회차 추세(G6 · [11.1])와 흔들림 파일(`results/_fixture_significance.json`)을 따로 쓴다.

## 입력 — 회차 폴더

없는 파일은 건너뛰고 그 칸은 「미측정」이 된다. 노드 이름은 파일에서 `app01` · `db02`(보고서에서는 `app-01` · `db-02`).

| 파일 | 쓰는 곳 |
| --- | --- |
| `meta.json` | 구간(`start_utc` · `end_utc`), 생성기 |
| `round.json` (측정자가 쓴다) | `scenario` · `changed` · `commit` · `migration` · `warmup_sec` · `notes` · `kind`(mix · solo · jitter). meta 위에 덮는다. 선택 키: `data_scale` · `tool` · `plan` · `command` · `operator` · `tracing_ratio` · `profiler` · `auto_explain_ms` |
| `jmeter/result.jtl` | 응답 시간의 정본. 워밍업(`warmup_sec`)을 뺀 구간만 통계에 쓴다 |
| `metrics/<노드>.prom.gz` | 노드 · 슬롯 · Redis · Nginx · PostgreSQL 지표(긁기마다 `# SCRAPE <epoch> <target>`, 실패는 `# SCRAPE_ERROR`) |
| `db/<노드>-wait.csv` · `-pgss.csv` · `-pgss-all.csv` · `-dbstats.csv` · `-auto-explain.log` | 대기 종류 · 질의 통계 · 회차 전후 적중률 · 실제 계획 |
| `nginx/access.log` | 경로별 rt · urt · uct · 바이트, 슬롯 분배(SSE `/api/notifications/stream` 은 지연 통계에서 뺀다) |
| `traces/spans.jsonl` | 요청당 SQL · DB 시간 · 메서드 자기 시간 · store(=Redis) 시간 · 트랜잭션 빈 시간. 깨진 줄은 건너뛴다 |
| `pre/pre.json` · `pre/credits.json` · `post/credits.json` | 회차 전 확인 · 크레딧 |
| `pre|post/redis-commandstats.txt` | Redis 명령 수 전후 차이 |
| `profiler/<노드>-<슬롯>-<모드>.collapsed` | 앱 CPU 분포 · JIT 비중 |
| `gen/vmstat.txt` | 부하 생성기 CPU |

## 출력 — `out/summary.json`

| 키 | 내용 | 양식 칸 |
| --- | --- | --- |
| `jtl` | 전체 · 레이블별 p50/p95/p99 · TPS · 오류율 · 5xx · 바이트, 10초 창 시계열, 포화 보조 판정 | [0.4] [1.4] G1 |
| `resources` | 노드 CPU(모드별) · load÷vCPU · 메모리 · 디스크 · TCP · conntrack · OOM, 슬롯별 스레드 · 풀 · 힙 · GC · 할당, PostgreSQL · Redis · Nginx, 헤드룸, 없는 지표 목록 | [4.6] [5.1] [6.4] [7.4] G2 |
| `db` | 대기 종류별 평균 활성 세션 · 질의 통계 상위 · 점유율 · auto_explain 집계 · dbstats | [4.1] [4.2] [4.6] |
| `traces` | 경로별 요청당 SQL · DB 시간 · Redis · 트랜잭션 점유 · 메서드 자기 시간 상위 · 행마다 불리는 메서드 · 반복 문장, 요청 밖 구간, DB 노드별 비중, 가장 느린 3건 | [5.3] [5.5] [6.2] [6.3] [6.7] [7.1] |
| `nginx` | 경로별 rt/urt/uct p95 · 바이트, 슬롯별 비중 | [5.1] [6.6] [7.1] |
| `budget` | 경로별 시간 예산(p95) — 입구 · 큐 · Service · 풀 · DB · Redis · 외부 · 나머지 · 설명되지 않은 시간 | [7.1] G4 |
| `matrix` | 구성 요소 × 경로 점유율 | [7.11] G7 |
| `endpoints` · `key` · `checks` · `comparison` | 신호등 행 · 핵심 지표 · 반영 확인 · 기준 대비 변화 | [1.4] [0.3] [7.10] |

계산 규칙

- 백분위는 선형 보간(numpy 기본 · Excel PERCENTILE.INC 와 같다). p95 는 표본 100 이상, p99 는 1,000 이상일 때만 판정용이고 그 밖은 「참고(표본 부족)」.
- 엔드포인트 키 = 경로에서 `/api/` 를 떼고 숫자 · 경로 변수를 `{id}` 로 바꾼 것. JMeter URL · Nginx 경로 · 추적 `http.route` 가 이 키로 이어진다.
- 메서드 자기 시간 = 구간 길이 − 자식 구간 합집합. Redis 시간 = `.store.` 패키지 메서드 구간. 트랜잭션 = 커밋(롤백) 구간 하나가 닫는 SQL 묶음, 빈 시간 = 그 안에서 SQL 이 없던 시간.
- 질의 통계 점유율은 `-pgss-all.csv` 가 있으면 전체 합 기준, 없으면 상위 20 합 기준(점유율이 부풀어 보인다 — `total_basis` 에 적힌다).
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
  "comparability": {"plan_cache_mode": {"base": "auto", "cur": "force_custom_plan", "impact": ""}},
  "comparability_verdict": "■ 조건이 같다",
  "endpoints": {"<JMeter 레이블>": {"target_p95_ms": 500, "single_limit_tps": 120, "headroom": 3.4}},
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
  "round_interval_min": 15, "cache_mode": "warm"
}
```

### 칸을 어디서 채우나

| 양식 칸 | 원천 |
| --- | --- |
| 표지 | 회차 범위 · 기간 · 하한 · 데이터 규모 · 도구는 자동, 판 · 작성자 · 이슈는 서술 |
| [0.2] 흔들림 표 · 하한 | `significance.json` |
| [0.3] 반영 확인 · 측정 수단 | 최근 6회차의 `checks` · `round.json` |
| [0.4](2)(5) | 포화 보조 판정 · 요청 수 · 쓸 수 있는 꼬리 |
| [1.1] [1.2] [1.3] | 서술(측정 줄 · [1.3]의 데이터 규모 · 커밋 · 크레딧 · 측정 수단은 자동) |
| [1.4] | 레이블별 p95 · 5xx · 병목 계층 · 주 자원 자동, 목표 · 단독 한계 · 헤드룸은 서술 |
| [2] [4.3] [4.4] [4.5] [4.7] [5.7] [6.1] [6.5] [6.8] [7.2] [7.3] [7.5] ~ [7.9] [12] | 사람 |
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
