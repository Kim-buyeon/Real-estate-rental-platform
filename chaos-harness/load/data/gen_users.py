"""부하 시험 사용자 축 — users · user_auth COPY 파일을 만든다 (#376).

- user_id 는 1억 번대(USER_ID_BASE + n). 1억 미만은 실사용자다.
- 비밀번호는 계정마다 다르다 — 번호와 비밀 시드(저장소 밖 SEED_FILE)의 HMAC 에서 나온다. JMeter 입력 CSV 에 같은 값을 쓴다.
- 해시는 앱과 같은 BCrypt 강도 10, 접두 $2a$ (SecurityConfig 의 BCryptPasswordEncoder 기본값).
- 칸별 분포 근거는 stats/user-distribution-sources.md. 근거가 없는 값은 「설계값」으로 표시한다.

실행: python gen_users.py --count 1000000 --out <폴더>
"""
import argparse
import base64
import csv
import gzip
import hashlib
import hmac
import os
import random
from datetime import datetime, timedelta
from multiprocessing import Pool

import bcrypt

USER_ID_BASE = 100_000_000
DATA_ROOT = os.environ.get('LOADTEST_DATA', r'C:\Users\bu200\loadtest-data')
SEED_FILE = os.path.join(DATA_ROOT, 'secret', 'password_seed.bin')
RNG_SEED = 20261003

# NICE 신용평점 구간별 인원(2025-12) — (하한, 상한 포함, 인원)
CREDIT_BANDS = [(900, 1000, 23_804_368), (800, 899, 10_712_715), (700, 799, 12_275_779), (600, 699, 613_240),
                (500, 599, 122_167), (400, 499, 47_922), (300, 399, 1_936_927), (200, 299, 171_734), (0, 199, 2_378)]
# 2025 가계금융복지조사 가구 소득 구간(원) — (하한, 상한, 비율). 1억 이상 구간의 상한 3억은 설계값
INCOME_BANDS = [(3_000_000, 10_000_000, 3.7), (10_000_000, 30_000_000, 20.4), (30_000_000, 50_000_000, 19.2),
                (50_000_000, 70_000_000, 15.9), (70_000_000, 100_000_000, 16.8), (100_000_000, 300_000_000, 23.9)]
HOUSE_OWNERSHIP_SEOUL = 0.481          # 2024 주택소유통계 서울
DEBT_HOLDER_RATE = 0.60                # 설계값 — 연령대별 금융부채 보유율 35.8 ~ 74.2% 사이
# 순자산 구간(원) — 3억 미만 57%, 10억 이상 11.8%(2025 가계금융복지조사). 구간 안 분포 · 30억 상한은 설계값
NET_ASSET_BANDS = [(0, 300_000_000, 57.0), (300_000_000, 1_000_000_000, 31.2), (1_000_000_000, 3_000_000_000, 11.8)]
PROFILE_FILLED_RATE = 0.70             # 설계값 — 나머지는 가입만 한 상태(앱 가입 기본값 0 · false)
# 2015 인구주택총조사 성씨(만 명). 나머지는 기타 성씨로 묶는다
SURNAMES = [('김', 1069), ('이', 730.7), ('박', 419.2), ('최', 233.4), ('정', 215.2), ('강', 117.7), ('조', 105.6),
            ('윤', 102.1), ('장', 99.3), ('임', 82.4), ('한', 77.3), ('오', 76.3), ('서', 75.2), ('신', 74.1),
            ('권', 70.6), ('황', 69.7), ('안', 68.5), ('송', 68.3), ('전', 55.9), ('홍', 55.8)]
GIVEN_NAMES = ['민준', '서준', '도윤', '예준', '시우', '하준', '주원', '지호', '지후', '준우', '서연', '서윤', '지우', '서현',
               '하은', '하윤', '민서', '지유', '윤서', '채원', '현우', '건우', '우진', '선우', '연우', '유준', '정우', '승우',
               '수아', '지아', '지민', '다은', '은서', '예은', '수빈', '지원', '소윤', '예린', '영숙', '정숙', '미경', '영희',
               '성호', '영수', '상철', '정호', '동현', '민수', '지훈', '성민']  # 설계값 — 출생 연대별 흔한 이름
SIGNUP_FROM = datetime(2025, 10, 1)
SIGNUP_DAYS = 367


def load_seed():
    os.makedirs(os.path.dirname(SEED_FILE), exist_ok=True)
    if not os.path.exists(SEED_FILE):
        with open(SEED_FILE, 'wb') as f:
            f.write(os.urandom(32))
    return open(SEED_FILE, 'rb').read()


def password_of(seed, n):
    digest = hmac.new(seed, str(n).encode(), hashlib.sha256).digest()
    return 'Lt' + base64.b32encode(digest).decode()[:14].lower()


def weighted(rng, items, weight_index):
    return rng.choices(items, weights=[it[weight_index] for it in items])[0]


def profile(rng):
    if rng.random() >= PROFILE_FILLED_RATE:
        return 0, 0, 0, 0, False, 0
    lo, hi, _ = weighted(rng, CREDIT_BANDS, 2)
    credit = rng.randint(max(lo, 1), hi)
    lo, hi, _ = weighted(rng, INCOME_BANDS, 2)
    income = round(rng.uniform(lo, hi), -5)
    has_house = rng.random() < HOUSE_OWNERSHIP_SEOUL
    if rng.random() < DEBT_HOLDER_RATE:
        loan = round(rng.lognormvariate(17.9, 1.0), -5)          # 중앙값 ≈ 5,900만, 평균 ≈ 9,700만 — 설계값
        payment = round(loan * rng.uniform(0.06, 0.15), -4)      # 연 원리금 6 ~ 15% — 설계값
    else:
        loan, payment = 0, 0
    lo, hi, _ = weighted(rng, NET_ASSET_BANDS, 2)
    own_fund = round(rng.uniform(lo, hi) * rng.uniform(0.1, 0.4), -5)  # 순자산 중 현금화 가능분 — 설계값
    return income, credit, loan, payment, has_house, own_fund


def hash_one(password):
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10, prefix=b'2a')).decode()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--count', type=int, default=1_000_000)
    parser.add_argument('--out', default=os.path.join(DATA_ROOT, 'out'))
    parser.add_argument('--workers', type=int, default=max(1, os.cpu_count() - 2))
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    seed = load_seed()
    rng = random.Random(RNG_SEED)

    users_path = os.path.join(args.out, 'users.csv.gz')
    auth_path = os.path.join(args.out, 'user_auth.csv.gz')
    creds_path = os.path.join(DATA_ROOT, 'secret', 'loadtest_credentials.csv.gz')
    chunk = 20_000
    with gzip.open(users_path, 'wt', newline='', encoding='utf-8') as fu, \
            gzip.open(auth_path, 'wt', newline='', encoding='utf-8') as fa, \
            gzip.open(creds_path, 'wt', newline='', encoding='utf-8') as fc, \
            Pool(args.workers) as pool:
        wu, wa, wc = csv.writer(fu), csv.writer(fa), csv.writer(fc)
        wc.writerow(['email', 'password'])
        for start in range(1, args.count + 1, chunk):
            numbers = range(start, min(start + chunk, args.count + 1))
            passwords = [password_of(seed, n) for n in numbers]
            hashes = pool.map(hash_one, passwords, chunksize=200)
            for n, password, hashed in zip(numbers, passwords, hashes):
                user_id = USER_ID_BASE + n
                email = 'loadtest+%07d@rental.test' % n
                name = weighted(rng, SURNAMES, 1)[0] + rng.choice(GIVEN_NAMES)
                income, credit, loan, payment, has_house, own_fund = profile(rng)
                created = SIGNUP_FROM + timedelta(seconds=rng.randrange(SIGNUP_DAYS * 86400))
                # users: user_id,name,email,phone,role,annual_income,credit_score,existing_loan,
                #        existing_loan_annual_payment,has_house,own_fund,created_at,deleted_at
                wu.writerow([user_id, name, email, '', 'USER', int(income), credit, int(loan), int(payment),
                             't' if has_house else 'f', int(own_fund), created.isoformat(sep=' '), ''])
                # user_auth: user_id,auth_type,provider_id,password_hash,last_login_at,created_at
                wa.writerow([user_id, 'EMAIL', email, hashed, '', created.isoformat(sep=' ')])
                wc.writerow([email, password])
            print('users', numbers[-1], flush=True)
    print('done', users_path, auth_path)


if __name__ == '__main__':
    main()
