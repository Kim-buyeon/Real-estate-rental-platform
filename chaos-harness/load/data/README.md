# 부하 시험 데이터 생성 (INF-06 · #376)

운영 DB 를 부하 시험 규모로 키운다 — 매물 500만(실 + 실거래 분포 기반 가짜), 사용자 100만, 관심 매물 ≈1,000만.
계획 · 결정은 이슈 #376 코멘트가 정본이다. 여기에는 다시 돌리는 방법과 함정만 둔다.

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

`.env.example` 의 `PROPERTY_BATCH_REFRESH_ENABLED` · `RISK_BATCH_REGISTRYREFRESH_ENABLED` · `RISK_BATCH_MOCKLEDGERREPLACE_ENABLED` · `BATCH_STARTUPCATCHUP_ENABLED`. 매물 갱신 배치는 적재기 메모리 수정(#376 계획 12번) 뒤에만 다시 켠다 — 자치구 엔티티 전체를 읽어 500만에서 힙이 넘친다. 등기 재조회는 복사한 등기가 Mock 값과 달라 켜면 관심 매물이 전부 「변동」이 된다(T7 때 별도 이슈).
