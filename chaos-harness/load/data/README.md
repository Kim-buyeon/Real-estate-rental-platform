# 부하 시험 데이터 생성 (INF-06 · #376)

운영 DB 를 부하 시험 규모로 키운다. 지금 쓰는 규모는 **매물 100만(실매물만, 가짜 0) · 사용자 30만**(INF-06 #390, 아래 「매물 100만」).
처음 판(#376, 2026-10-03)은 매물 500만(실 + 가짜) · 사용자 100만이었고 운영 DB 를 그 전 백업으로 되돌렸다 — 그 순서는 「#376 순서(기록)」.
계획 · 결정은 이슈 코멘트(#390 마지막 계획 · #376)가 정본이다. 여기에는 다시 돌리는 방법 · 자원 계산 · 함정만 둔다.

## 원천 (저장소 밖 — `LOADTEST_DATA`, 기본 `C:\Users\bu200\loadtest-data`)

| 경로 | 무엇 | 받는 법 |
|---|---|---|
| `rent/rent_<연도>/` | 서울 열린데이터광장 OA-21276 「서울시 부동산 전월세가 정보」 2011~2025 (cp949, 공공누리 1유형) | `POST https://datafile.seoul.go.kr/bigfile/iot/inf/nio_download.do` · `infId=OA-21276&infSeq=3&seq=<24~40>` |
| `sale/{apt,offi}/<자치구>/<YYYYMM>_<p>.xml` | 국토부 매매 실거래(시세 표본) 2010-01~ | `fetch_sale.py` — 이어 받기 |
| `stats/user-distribution-sources.md` | 사용자 칸 분포의 출처(NICE · 가계금융복지조사 · 주택소유통계 · 성씨) | — |
| `secret/` | 비밀번호 시드 · 생성 계정 자격 증명 | 커밋 금지 |

## 순서

로컬 (Python 3, `pip install bcrypt`):

| # | 스크립트 | 출력(`out/`) |
|---|---|---|
| 1 | `prep_rent.py` — 아파트 · 오피스텔, 대지 지번 · 층 있는 행 | `rent_usable.csv.gz` · `buildings.csv.gz` |
| 2 | `resolve_address.py` — 건물당 주소 정규화 · 좌표 1회(앱과 같은 API · 같은 첫 건) | `address_cache.jsonl` |
| 3 | `gen_properties.py --fake N` — 실매물 변환 + 같은 건물 · 같은 면적 실거래로 가짜 | `property_real.csv.gz` · `property_fake.csv.gz` |
| 4 | `gen_users.py` — 계정마다 다른 비밀번호 · BCrypt(강도 10, `$2a$`) | `users.csv.gz` · `user_auth.csv.gz` |

앱 규칙은 `appcompat.py` 에 옮겼다(시세 중앙값 · 면적대 · 임대인명 · 계약 유형 · 대장 조회 키). 앱 코드가 바뀌면 같이 바꾼다 — 임대인명은 Java 원본과 출력 대조로 확인했다.

## 매물 100만 (INF-06 #390)

목표 — 매물 1,000,000 = 기준선 312,661 + 실매물 풀(`property_real.csv.gz`, ≈233만)에서 **687,339**. 가짜 0. 사용자 300,000.

| 축 | 목표 | 어떻게 |
|---|---|---|
| 매물 | 자치구별 = 풀에서 그 구 비율 × 687,339 (최대 잉여법), 구 안은 md5(자연키 ‖ `inf06-1m`) 순 | `05_plan.sql` — 기준선과 자연키가 같은 실매물을 먼저 뺀다(`out/prod_baseline.csv.gz` 와 같은 31만) |
| 등기 · 갑구 · 을구 | 1:1 · ≈2.18 · ≈0.90 | 판정 원본 복사(`20_bundle.sql`, #376 그대로). 비율은 원본 풀 평균이 정한다 — `90_verify` ⑨ |
| 대장 | **100%** | 새 매물은 실대장 → 원본 대장(MOCK) → 매물에서 지은 MOCK. 기준선 중 대장 없는 ≈24만은 `25_ledger_fill.sql`(MOCK) |
| 판정 | 최신 1 + 이력 ≈0.94 | 결론 · 근거 JSON · 지문을 앱 산식으로(`54_judgement.sql`), 이력 33 · 40 · 27%(`30_history.sql`) |
| 매물 비정규화 열(V22) | 최신 판정과 같게 | `54_judgement.sql` 끝 — V23 과 같은 문장을 구간으로 |
| 사용자 · 인증 | 300,000 | 기준선(1억 미만) + 생성기 앞 N명 — `copy-users` 가 파일 앞 N줄만 넣는다 |
| 구독 | 생성기 사용자 30% × 5행, 시험 계정 전부 | 토글 3 + 신규 매물 자치구 2(`40_users.sql`) |
| 관심 | 평균 5, 시험 계정 30 ~ 50 | `41_wishlist.sql`(1 + ⌊Exp(4.5)⌋) · `43_test_accounts.sql` |
| 알림 | 평균 10, 시험 계정 수백(안 읽음 30%) | `42_notification.sql` — 알림 1건 = `notification` 1 + `wishlist_notification` 1 |

시험 계정 = 이메일 `jmeter+%@rental.test`(`make-accounts.sh` · 가입 API). **`users` 단계 전에 가입이 끝나 있어야 한다** — 40 이 그때의 계정으로 `loadtest.test_users` 를 만든다.

### V21 ~ V27 에 맞춘 것

- **V21 판정 근거 · 지문** — 위험도 · 대출 조회는 최신 행의 `judgement_snapshot` 이 있고 `criteria_fingerprint` 가 지금 기준의 지문과 같고 매물이 재분석 대기가 아니면 판정하지 않는다(`RiskAnalysisCommandService.findStoredJudgement`). 하나라도 어긋나면 조회마다 판정 · 쓰기를 한다.
  - 지문은 매물과 무관하다(기준표 6종 + 대장 모드 `real`). `53_fingerprint.sql` 이 앱의 정규 문자열(`CriteriaFingerprintCalculator`)을 SQL 로 펴서 SHA-256 을 내고, 운영 최신 행에 앱이 적은 지문이 있으면 그 값과 다를 때 멈춘다.
  - 근거 JSON 은 매물마다 다르다(보증한도 · 예상 보증료 · 위배 조건 · 권리 · 정합). `54_judgement.sql` 이 `51_guarantee_calc.inc.sql` 의 입력으로 `RiskResponse.Judgement` 모양을 짓는다. 기관별 가입 가능이 51 의 결론과 하나라도 다르면 멈춘다.
  - 기준선 31만도 근거가 비어 있다(V21 은 채우지 않는다) — `snapshot-baseline` 이 결론이 앱 산식과 같은 행만 채운다. 다른 행은 비워 앱이 조회 때 다시 판정한다(건수가 출력된다).
- **V18 재분석 대기** — 넣는 행은 기본값 FALSE. 기준선에 TRUE 가 남아 있으면 그 매물은 조회 때 판정한다 — `90_verify` ⑧ `reanalysis_pending`.
- **V22 ~ V24 매물 최신 판정 열** — `54_judgement.sql` 이 구간마다 맞춘다. 인덱스(V24 커버링 · 전세가율)는 이미 있으므로 넣을 때 유지 비용으로만 든다.
- **V25 ~ V27 인덱스** — 만들 것이 없다. 매물 인덱스가 V24 · V26 · V27 로 셋 늘어 매물 1건 넣기 · 고치기(54 의 열 갱신)마다 인덱스 항목이 그만큼 더 쓰인다 — 아래 계산의 매물 행 크기(기준선 실측)에 이미 들어 있다.

### 순서

DB-01, `deploy`, `~/loadtest/{in,sql,log}`. 입력은 `in/property_real.csv.gz` · `in/users.csv.gz` · `in/user_auth.csv.gz`(풀 전체 — 상한은 DB 에서 고른다).
시작 전: 시험 동안 끄는 배치 넷이 꺼져 있는지(아래), `loadtest` 스키마가 없는지(`\dn loadtest` — 있으면 옛 기준선이다. 지우고 시작), `EBSByteBalance%` · `CPUCreditBalance` · 루트 디스크 여유(WAL 로컬 사본, 「함정」 첫째 줄).

```
./run.sh guarantee-check                        # 0. 기준선 불일치 0 확인(51) — 0 이 아니면 그 건수가 snapshot-baseline 에서 비워진다
./run.sh stage                                  # 1. loadtest 스키마 · 기준선(property_count 312,661 이어야 한다)
./run.sh copy-property in/property_real.csv.gz  # 2. 풀 전체(UNLOGGED)
./run.sh plan 1000000                           # 3. 구별 몫 · 고른 68.7만만 남긴다 — 출력의 new_total 687,339 · property_total_after 1,000,000
./run.sh template                               # 4. 판정 원본 풀 · 건물 실대장
./run.sh fingerprint                            # 5. 지문 — ok = t 여야 한다
./run.sh all                                    # 6. 25개 구: 매물 → 묶음(대장 100%) → 결론 · 근거 · 지문 · 매물 열 → 이력, 구마다 standby 대기
./run.sh ledger-fill 1 312661                   # 7. 기준선 대장 채우기(MOCK, 10만씩)
./run.sh snapshot-baseline 1 312661             # 8. 기준선 근거 · 지문(10만씩)
./run.sh drop-stage                             # 9. 적재용 표 지우기(풀 · 원본 풀 · 실대장 표)
./run.sh copy-users in/users.csv.gz in/user_auth.csv.gz   # 10. 앞 (30만 − 기준선 사용자)명만
./run.sh users                                  # 11. 사용자 · 인증 · 시험 계정 표 · 구독
./run.sh pick                                   # 12. 관심 고르기 표
./run.sh wishlist 100000001 100299999 100000    # 13. 생성기 사용자 관심(평균 5)
./run.sh test-wishlist                          # 14. 시험 계정 관심(30 ~ 50)
./run.sh notify 100000001 100299999 50000       # 15. 생성기 사용자 알림(평균 10) — 첫 구간에서 변화 표(loadtest.trans)를 만든다
./run.sh notify-test                            # 16. 시험 계정 알림(수백)
./run.sh vacuum                                 # 17. VACUUM (ANALYZE) 표마다 — 54 의 매물 열 갱신이 남긴 죽은 판 정리 · 가시성 맵 · 통계
./run.sh verify                                 # 18. 위반 0 · ⑧ ~ ⑩ 비율
```

- 60만 중간 점검을 하려면 6 을 구 몇 개씩 나눠 돌린다(`./run.sh all 종로구 중구 …`) — 구마다 커밋이라 어디서 멈춰도 같은 명령으로 이어 간다. 고르기(3)는 100만 기준 한 번뿐이다(반영을 시작한 뒤 다시 돌리면 멈춘다).
- 첫 구(종로구)의 `끝 (Ns)` 와 `plan` 출력의 몫으로 전체 시간을 어림한다 — 구마다 시간 ≈ 몫에 비례.
- 끊기면 같은 명령을 다시 — 매물은 `property_id` 가 찬 행, 묶음은 `bundled`, 54 는 같은 값이면 쓰지 않고, 이력은 이력이 있는 매물, 관심 · 알림은 이미 있는 사용자 · 관심을 건너뛴다.
- 되돌리기는 #376 과 같다 — `loadtest.baseline` 의 LSN 으로 PITR, 또는 반영 전 물리 백업.

### 확인 — 저장된 판정이 쓰이는가(반영 뒤 · 시험 전)

SQL 로 지은 근거를 앱이 읽는지는 로컬에서 확인하지 못했다(로컬 postgres 없음). 운영에서 이렇게 본다 — 저장된 판정을 돌려주는 조회는 갑구를 읽지 않고, 판정은 쓰기 트랜잭션이라 primary 에서 돈다.

```sql
-- DB-01(primary)에서 전후로 두 번
SELECT relname, seq_scan + coalesce(idx_scan, 0) AS scans, n_tup_upd, n_tup_ins
  FROM pg_stat_user_tables WHERE relname IN ('ownership_history', 'risk_analysis');
```

그 사이에 새 매물 10건 · 기준선 10건에 `GET /api/properties/{id}/risk` 를 부른다. `ownership_history` scans · `risk_analysis` n_tup_upd · n_tup_ins 가 늘지 않아야 한다. 늘었으면 그 매물의 최신 행 `judgement_snapshot` · `criteria_fingerprint` 를 조회 전후로 비교한다(앱이 다시 적은 JSON 과 SQL 이 지은 JSON 의 차이가 원인).

### 자원 계산 (2026-10-05 운영 실측에서)

기준선 표 크기(pg_stat_user_tables, 인덱스 포함으로 본다)를 행 수로 나눈 행당 크기:

| 표 | 크기 ÷ 행 | 행당 | 매물당 |
|---|---|---|---|
| property | 324 MiB ÷ 312,661 | 1,087 B | 1,087 B + 54 의 열 갱신으로 한 판 더(VACUUM 전까지 죽은 판, 파일은 줄지 않는다) = **2,174 B** |
| building_registry | 72 MiB ÷ 312,661 | 241 B | 241 B |
| ownership_history | 102 MiB ÷ 681,345 | 157 B | × 2.18 = 342 B |
| mortgage_history | 57 MiB ÷ 281,168 | 213 B | × 0.90 = 191 B |
| risk_analysis | 116 MiB ÷ 605,606 | 201 B | × 1.94 = 390 B + 최신 행 근거 JSON ≈ 1,000 B(기관 3 × ≈180 + 나머지 ≈420, 2 KB 아래라 TOAST 안 됨) = **1,390 B** |
| building_ledger | 18 MiB ÷ 68,286 | 276 B | × 1.00 = 276 B |
| **새 매물 1건** | | | **≈ 4,610 B** |

- 맞춰 보기 — #376 의 매물 1건(근거 없음 · 열 갱신 없음 · 대장 22%) = 1,087 + 241 + 342 + 191 + 390 + 0.22 × 276 ≈ 2,310 B × 468만 ≈ 10.8 GB, 사용자 축 ≈ 5 GB 와 합쳐 README 의 「500만 ≈ 16 GB」와 맞는다.
- 사용자 축(행당 크기 미실측 — 운영 202행으로는 못 잰다. 행 + 인덱스 어림): users + user_auth ≈ 600 B × 30만 = 0.17 GiB, 관심 ≈ 180 B × 150만 = 0.25 GiB, 알림 ≈ 150 B × 300만 = 0.42 GiB, 연결 ≈ 170 B × 300만 = 0.48 GiB, 구독 ≈ 150 B × 45만 = 0.06 GiB → **≈ 1.4 GiB**.

| 단계 | 넣는 것 | 데이터 증가 | 누적 DB |
|---|---|---|---|
| 지금 | — | — | ≈ 0.69 GiB |
| 31만 → 60만 | 새 매물 287,339 × 4,610 B | 1.23 GiB | ≈ 1.9 GiB |
| 60만 → 100만 | 새 매물 400,000 × 4,610 B | 1.72 GiB | ≈ 3.6 GiB |
| 기준선 대장 · 근거 | 244,375 × 276 B + 312,661 × (1,000 + 201 죽은 판) B | 0.42 GiB | ≈ 4.1 GiB |
| 사용자 축 | 위 | 1.4 GiB | **≈ 5.5 GiB** |
| 잠깐(적재용 표, UNLOGGED) | 풀 233만 ≈ 0.9 GiB + 고른 표 0.25 GiB | 지우면 돌아온다 | 최고 ≈ 6.6 GiB |

데이터 볼륨 20 GB 의 ≈ 33% — `disk_guard`(88%)에 닿지 않는다.

- **WAL** ≈ 기록한 데이터 × 1 ~ 1.3(#376 — 4시간 16 GB 데이터에 로컬 WAL 사본 16 GB, 체크포인트 뒤 첫 수정 페이지 전체 기록이 더해진다). 적재용 표는 UNLOGGED 라 빠진다. (4.1 − 0.69) + 1.4 ≈ 4.8 GiB × 1 ~ 1.3 ≈ **5 ~ 6.5 GiB**, VACUUM 의 가시성 맵 · 힌트 비트 기록까지 넉넉히 **≈ 8 GiB**. 단계별 60만까지 ≈ 1.3 ~ 1.7 GiB, 100만까지 ≈ +1.8 ~ 2.3 GiB, 나머지 사용자 축 · 기준선.
  WAL 로컬 사본(`/var/backups/rental/wal`, 루트 디스크)에 같은 양이 쌓인다 — #381 정리(S3 보낸 것만 지움)가 도는지, 루트 여유가 8 GiB 이상인지 먼저 본다.
- **EBS** — t3.small 인스턴스 대역폭 기본 21.75 MB/s · 버스트 260.6 MB/s, 버킷 = (260.6 − 21.75) × 1,800초 ≈ 430 GB(#376). 이번 디스크 IO 어림 = 데이터 쓰기 6.6 + WAL 쓰기 8 + 로컬 사본 쓰기 8 + 사본 읽기(S3 전송) 8 + 체크포인트 재기록 ≈ 3 + 원본 읽기 ≈ 2 ≈ **35 GB**. 버킷의 ≈ 8% — 버스트가 99% 면 바닥나지 않는다. 버스트로 35,000 MB ÷ 260.6 MB/s ≈ 2.2분, 버킷이 비었을 때 기본 속도로 35,000 ÷ 21.75 ≈ 1,610초 ≈ **27분**(순수 IO 만, 단계별 60만까지 ≈ 9분 · 100만까지 ≈ +12분 · 나머지 ≈ 6분). 시간을 정하는 것은 IO 가 아니라 CPU(t3 CPU 크레딧 — md5 고르기 · 근거 JSON · BCrypt 는 없음)와 단일 프로세스 무작위 읽기일 공산이 크다 — **소요 시간은 미실측**, 첫 구로 잰다.
- 함께 볼 것 — `CPUCreditBalance`(t3.small 2 vCPU), standby 따라잡기(`wait_standby` — 슬롯이 1 GB 넘게 붙잡으면 기다린다).

## #376 순서(기록 — 매물 500만, 2026-10-03)

운영 (DB-01, `deploy`, 파일은 `~/loadtest/{in,sql,log}`):

```
./run.sh stage                                  # loadtest 스키마 · 기준선(최대 식별자 · LSN)
./run.sh copy-property in/property_real.csv.gz  # 적재용 표(UNLOGGED)
./run.sh template                               # 판정 원본 풀 · 건물 실대장
./run.sh all <fake_limit> [구...]               # 구마다 매물 → 판정 묶음 → 이력 → standby 대기
./fake_follow.sh <fake_limit> <실매물 로그>      # 가짜를 실매물이 끝난 구에만 뒤따라 붙인다(같은 구 동시 처리 금지)
./run.sh copy-users in/users.csv.gz in/user_auth.csv.gz
./after_property.sh                             # 반복이 끝나면 users → analyze → verify
# 2026-10-03 재개분 — 40_users.sql 의 관심 매물 · 알림을 한 문장으로 돌리면 끝의 외래키 확인이 1시간 넘게 걸린다(함정).
./run.sh pick                                   # 관심 매물 고르기 표
./run.sh wishlist 100000001 101000000 100000    # 사용자 10만 명 구간마다 커밋, 매물 식별자 순서
./run.sh owner-fix                              # 보증 판정 보정 ① 소유자 불일치(50_owner_mismatch.sql)
./run.sh notify                                 # 알림(42_notification.sql) — 크레딧이 찬 뒤
```

`fake_limit` = 500만 − (기준선 매물 + 새 실매물). 모든 단계는 구 단위 트랜잭션이라 끊기면 같은 명령을 다시 돌린다(이미 넣은 것은 건너뛴다).
되돌리기는 `loadtest.baseline` 의 LSN 으로 PITR. 실 · 가짜 구분은 `loadtest.property_origin.kind`.

## 보증 3사 판정 보정 ② (`51_*` · `52_*`)

판정 묶음은 보증 3사 결과를 원본에서 복사했다(`20_bundle.sql`). 새 매물의 보증금 · 시세 · 선순위채권 · 대장이 원본과 달라 앱이 낼 값과 다를 수 있다 — 소유자 불일치만 보정 ①(`50_owner_mismatch.sql`)이 고쳤다. 보정 ②는 앱 판정 전체(깡통전세 · 3사 가입 · 등급 · 사유 · 가입 기관 · 전세가율)를 SQL 로 다시 내 다른 행만 고친다.

| 파일 | 하는 일 |
|---|---|
| `51_guarantee_calc.inc.sql` | 공용 계산부 — 세션 임시 뷰 `gc_calc`. 앱과 맞춘 규칙 · 가정 · 기대는 색인이 머리 주석에 있다. 혼자 돌리지 않는다 |
| `51_guarantee_check.sql` | 읽기 전용 대조 — 항목별 불일치 건수, 대장 출처별 분포, 표본 20건 |
| `52_guarantee_recalc.sql` | 새 매물(`loadtest.property_origin`)의 최신 판정 행을 제자리 갱신. 이력 · `previous_grade` · `ledger_id` 는 그대로 |

```
./run.sh guarantee-check                       # 1. 기준선(앱이 판정한 31만) — 불일치 전부 0 이어야 다음으로
./run.sh guarantee-check new 312667 812666     # 2. 새 매물 한 구간 미리 보기(고칠 건수)
./run.sh guarantee-recalc 312667 4993952       # 3. 구간(기본 20만)마다 커밋 · 디스크 확인 · standby 대기
./run.sh guarantee-check-all 312667 4993952      # 4. 새 매물 전체를 50만씩 — 구간마다 0 이어야 한다(한 번에 돌리면 임시 표가 수 GB)
./run.sh notify                                # 5. 알림은 그 뒤(최신 행의 previous_grade → risk_grade 를 읽는다)
```

- 운영 대장 모드는 real 로 본다 — MOCK 대장은 「대장 없음」(`-v ignore_mock_ledger=false` 로 끈다). 기준선 불일치가 MOCK 행에 몰리면 판정 시점의 모드 차이다.
- 기준선 불일치가 0 이 아니면 52 를 돌리지 않는다 — 표본의 입력으로 산식 차이인지(고칠 것) 저장 뒤 입력이 바뀐 것인지(앱도 재판정 전까지 옛 값을 둔다) 먼저 가른다.
- 로컬 확인(2026-10-03): postgres:17 에 V1 ~ V20 + 계산기 단위 테스트 경계 케이스 37건(저장값 = Java 기대값) → 불일치 0. MOCK 무시를 끄면 MOCK 2건이, 틀린 저장값을 둔 새 매물 2건이 잡히고 52 뒤 0.

## 함정 (2026-10-03 실측)

- **DB-01 루트 디스크가 WAL 로컬 아카이브로 찬다.** `/var/backups/rental/wal` 은 S3 로 보낸 뒤에도 주 1회 물리 백업까지 남는다 — 반영 4시간에 16GB 로 100%. 반영 동안 S3 보낸 표시가 있는 것만 지우는 임시 타이머를 둔다(systemd-run 에 셸 한 줄을 넘기면 `;` `$` 가 깨진다 — 스크립트 파일로). 재부팅하면 사라진다.
- **t3.small 디스크 버스트가 바닥난다.** 기본 21.75MB/s · 1,000 IOPS, 버킷 = (260.6 − 21.75) × 1,800초 ≈ 430GB. 04:58 에 0% 가 된 뒤 구당 시간이 4 ~ 10배로 늘었다. 한가하면 ≈ 5.5시간에 가득 찬다(AWS 공식). 부하 시험 전에 `EBSByteBalance%` 를 본다.
- **`INSERT … SELECT` 대량은 끝에 외래키 확인이 몰린다.** 관심 매물 950만은 넣기가 끝난 뒤 행마다 매물 · 사용자 존재 확인(+ 매물 행 KEY SHARE 잠금)을 해 메모리보다 큰 매물 테이블을 무작위로 읽는다.
- 중복 확인은 기준선 이하 매물만 본다 — 생성기가 실 · 가짜 자연키가 겹치지 않게 만들었다. 전체를 보면 구마다 수백만 건을 훑는다.
- 상관 서브쿼리를 임시 표에 걸면 행 수 제곱이다(이력 단계 — 조인 + 색인으로 바꿨다).
- 데이터 볼륨: 500만 + 사용자 축에 20GB 중 ≈ 16GB. 적재용 표(≈2.4GB)는 매물 반영이 끝나면 바로 지운다.

## 시험 동안 끄는 배치

`.env.example` 의 `PROPERTY_BATCH_REFRESH_ENABLED` · `RISK_BATCH_REGISTRYREFRESH_ENABLED` · `RISK_BATCH_MOCKLEDGERREPLACE_ENABLED` · `BATCH_STARTUPCATCHUP_ENABLED`. 매물 갱신 배치의 적재기는 자치구 매물 엔티티 전체가 아니라 비교에 필요한 열만 읽도록 바꿨다(#383) — 행당 크기를 필드 수로 어림했을 뿐 힙에 들어가는지는 재지 않았다. 다시 켜는 것은 운영 작업으로 따로 하고, 그때 슬롯 힙과 자치구별 최대 건수를 실측한다. 등기 재조회는 복사한 등기가 Mock 값과 달라 켜면 관심 매물이 전부 「변동」이 된다(T7 때 별도 이슈).
