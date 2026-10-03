"""매물 COPY 파일 — 실매물(서울시 전월세 파일) + 같은 건물 실거래 분포에서 만든 가짜 (#376).

입력: out/rent_usable.csv.gz · out/address_cache.jsonl · <DATA_ROOT>/sale/{apt,offi}/<자치구>/<YYYYMM>_<p>.xml
출력: out/property_real.csv.gz · out/property_fake.csv.gz · out/property_report.json

실매물은 앱 적재기와 같은 규칙으로 옮긴다(appcompat). 같은 자연키는 앱처럼 최근 달의 행이 남는다.
가짜는 실매물이 나온 건물에서만, 그 건물 · 그 면적의 실거래 분포로 만든다 — 계획(#376 코멘트) 「property 가짜」.
"""
import argparse
import csv
import glob
import gzip
import json
import os
import random
import re
from collections import defaultdict
from datetime import date
from decimal import Decimal

from appcompat import (MarketPrices, area_scale2, contract_type, is_intentional_mismatch, landlord_name, ledger_key,
                       natural_key_text, other_name)

DATA_ROOT = os.environ.get('LOADTEST_DATA', r'C:\Users\bu200\loadtest-data')
OUT = os.path.join(DATA_ROOT, 'out')
PRICE_MONTHS = [f'{y}{m:02d}' for y, m in [(2025, m) for m in range(10, 13)] + [(2026, m) for m in range(1, 10)]]
RECENT_FROM = '20231001'           # 가짜 금액의 근거 — 최근 3년 계약
DEPOSIT_STEP = 1_000_000           # 가짜 보증금 반올림 단위(100만 원)
RENT_STEP = 10_000                 # 가짜 월세 반올림 단위(1만 원)
DEFAULT_CONVERSION = 0.05          # 건물 전환율을 못 구할 때 — 설계값
FAKE_SEED = 20261003
COLUMNS = ['kind', 'seq', 'address', 'district', 'landlord_name', 'contract_type', 'property_type', 'deposit',
           'monthly_rent', 'market_price', 'price_type', 'price_date', 'area_sqm', 'floor', 'built_year', 'latitude',
           'longitude', 'sigungu_code', 'bjdong_code', 'bun', 'ji', 'registry_owner']
ITEM = re.compile(r'<item>(.*?)</item>', re.S)


def tag(xml, name):
    m = re.search('<%s>([^<]*)</%s>' % (name, name), xml)
    return m.group(1).strip() if m else None


def load_market_prices():
    """자치구별 MarketPrices — 앱 RealSaleTransactionClient · MarketPriceCalculator 와 같은 표본 규칙."""
    by_gu = defaultdict(list)
    for kind, ptype in (('apt', 'APARTMENT'), ('offi', 'OFFICETEL')):
        for gu_dir in glob.glob(os.path.join(DATA_ROOT, 'sale', kind, '*')):
            gu = os.path.basename(gu_dir)
            for ym in PRICE_MONTHS:
                for path in glob.glob(os.path.join(gu_dir, ym + '_*.xml')):
                    for item in ITEM.findall(open(path, encoding='utf-8').read()):
                        area, amount = tag(item, 'excluUseAr'), tag(item, 'dealAmount')
                        y, m, d = tag(item, 'dealYear'), tag(item, 'dealMonth'), tag(item, 'dealDay')
                        try:
                            area = Decimal(area) if area else None
                            amount = int(amount.replace(',', '')) * 10_000 if amount else None
                            cdate = date(int(y), int(m), int(d)) if y and m and d else None
                        except (ValueError, ArithmeticError):
                            continue
                        if area is None or amount is None or cdate is None:
                            continue
                        cancelled = bool((tag(item, 'cdealType') or '').strip())
                        by_gu[gu].append((ptype, tag(item, 'umdNm'), area, amount, cdate, cancelled))
    return {gu: MarketPrices(sales) for gu, sales in by_gu.items()}


def load_addresses():
    table = {}
    for line in open(os.path.join(OUT, 'address_cache.jsonl'), encoding='utf-8'):
        r = json.loads(line)
        if not r.get('err'):
            table[tuple(r['key'])] = r
    return table


def property_row(kind, seq, addr, district, dong, sgg, jibun, ptype, area, floor, deposit, rent, build_year, prices):
    price = prices.find(ptype, dong, area)
    if price is None:
        return None, None
    key_text = natural_key_text(addr['road'], area, floor, deposit, rent)
    lkey = ledger_key(sgg, addr['adm'], jibun) or ('', '', '', '')
    landlord = landlord_name(key_text)
    # 등기 현재 소유자 — Mock 등기와 같은 규칙: 의도적 불일치(20%)만 다른 이름
    owner = other_name(landlord, key_text) if is_intentional_mismatch(key_text) else landlord
    row = [kind, seq, addr['road'], district, landlord, contract_type(deposit, rent), ptype, deposit,
           rent, price[0], 'ACTUAL_TRANSACTION', price[1].isoformat(), str(area_scale2(area)), floor,
           build_year or '', addr['lat'], addr['lng'], *lkey, owner]
    return row, (addr['road'], str(area_scale2(area)), floor, deposit, rent)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fake', type=int, default=2_600_000, help='만들 가짜 수 — 반영 때 필요한 만큼만 앞에서 쓴다')
    args = parser.parse_args()
    prices = load_market_prices()
    addresses = load_addresses()
    print('market tables', len(prices), 'addresses', len(addresses), flush=True)

    rows = list(csv.DictReader(gzip.open(os.path.join(OUT, 'rent_usable.csv.gz'), 'rt', encoding='utf-8')))
    rows.sort(key=lambda r: r['contract_date'], reverse=True)   # 앱은 최근 달부터 — 같은 자연키는 최근 행이 남는다
    seen = set()
    report = defaultdict(int)
    by_building = defaultdict(list)
    with gzip.open(os.path.join(OUT, 'property_real.csv.gz'), 'wt', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(COLUMNS)
        for r in rows:
            bkey = (r['sgg_code'], r['bjd_code'], r['bon'], r['bu'])
            addr = addresses.get(bkey)
            if addr is None:
                report['skip_address'] += 1
                continue
            area, floor = Decimal(r['area']), int(r['floor'])
            deposit, rent = int(r['deposit']), int(r['rent'])
            row, key = property_row('REAL', 0, addr, r['sgg_name'], r['dong'], r['sgg_code'], r['jibun'], r['ptype'],
                                    area, floor, deposit, rent, r['build_year'], prices[r['sgg_code']])
            if row is None:
                report['skip_market_price'] += 1
                continue
            by_building[bkey].append((r, area, floor, deposit, rent))
            if key in seen:
                report['skip_duplicate'] += 1
                continue
            seen.add(key)
            w.writerow(row)
            report['real'] += 1
    print('real', dict(report), flush=True)

    # ---- 가짜 -----------------------------------------------------------------------------------------
    rng = random.Random(FAKE_SEED)
    buildings, weights = [], []
    for bkey, items in by_building.items():
        recent = [it for it in items if it[0]['contract_date'] >= RECENT_FROM]
        if recent:
            buildings.append((bkey, items, recent))
            weights.append(len(items))
    profiles = {}

    def profile(b):
        bkey, items, recent = b
        if bkey in profiles:
            return profiles[bkey]
        floors = [it[2] for it in items]
        by_area = defaultdict(lambda: {'jeonse': [], 'wolse': []})
        for it in recent:
            by_area[str(area_scale2(it[1]))]['jeonse' if it[4] == 0 else 'wolse'].append(it)
        areas = list(by_area.keys())
        area_weights = [len(v['jeonse']) + len(v['wolse']) for v in by_area.values()]
        conv = {}
        for a, v in by_area.items():
            if v['jeonse'] and v['wolse']:
                jm = sorted(it[3] for it in v['jeonse'])[len(v['jeonse']) // 2]
                rates = sorted(12 * it[4] / (jm - it[3]) for it in v['wolse'] if it[3] < jm)
                conv[a] = min(max(rates[len(rates) // 2], 0.02), 0.10) if rates else DEFAULT_CONVERSION
            else:
                conv[a] = DEFAULT_CONVERSION
        profiles[bkey] = (floors, by_area, areas, area_weights, conv)
        return profiles[bkey]

    made = attempts = 0
    with gzip.open(os.path.join(OUT, 'property_fake.csv.gz'), 'wt', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(COLUMNS)
        while made < args.fake:
            attempts += 1
            b = rng.choices(buildings, weights=weights)[0]
            floors, by_area, areas, area_weights, conv = profile(b)
            a = rng.choices(areas, weights=area_weights)[0]
            pool = by_area[a]
            kind = 'jeonse' if rng.random() < len(pool['jeonse']) / (len(pool['jeonse']) + len(pool['wolse'])) else 'wolse'
            src = rng.choice(pool[kind])
            r = src[0]
            floor = rng.choice(floors)
            if kind == 'jeonse':
                deposit = max(DEPOSIT_STEP, round(src[3] * rng.uniform(0.97, 1.03) / DEPOSIT_STEP) * DEPOSIT_STEP)
                rent = 0
            else:
                deposit = max(DEPOSIT_STEP, round(src[3] * rng.uniform(0.9, 1.1) / DEPOSIT_STEP) * DEPOSIT_STEP)
                rent = src[4] + (src[3] - deposit) * conv[a] / 12
                rent = max(RENT_STEP, round(rent / RENT_STEP) * RENT_STEP)
            addr = addresses[b[0]]
            row, key = property_row('FAKE', made + 1, addr, r['sgg_name'], r['dong'], r['sgg_code'], r['jibun'],
                                    r['ptype'], src[1], floor, int(deposit), int(rent), r['build_year'],
                                    prices[r['sgg_code']])
            if row is None or key in seen:
                continue
            seen.add(key)
            w.writerow(row)
            made += 1
            if made % 500_000 == 0:
                print('fake', made, flush=True)
    report['fake'] = made
    report['fake_attempts'] = attempts
    report['fake_buildings'] = len(buildings)
    json.dump(report, open(os.path.join(OUT, 'property_report.json'), 'w'), indent=1)
    print(dict(report))


if __name__ == '__main__':
    main()
