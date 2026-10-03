"""앱 값 대조 (#376 계획 순서 2). 운영의 반영 전 매물(앱 적재기가 만든 값)과 생성기 실매물 출력에서
자연키가 같은 행끼리 임대인명 · 시세 · 시세 기준일 · 대장 조회 키 · 좌표를 비교한다.

입력: out/prod_baseline.csv.gz — 운영에서 내려받은 반영 전 매물
        COPY (SELECT address, area_sqm, floor, deposit, monthly_rent, landlord_name, market_price, price_date,
                     sigungu_code, bjdong_code, bun, ji, latitude, longitude
                FROM property WHERE property_id <= (SELECT max_property_id FROM loadtest.baseline)) TO STDOUT (FORMAT csv)
      out/property_real.csv.gz — gen_properties.py 출력
"""
import csv
import gzip
import os
from collections import Counter
from decimal import Decimal

DATA_ROOT = os.environ.get('LOADTEST_DATA', r'C:\Users\bu200\loadtest-data')
OUT = os.path.join(DATA_ROOT, 'out')


def key(address, area, floor, deposit, rent):
    return address, Decimal(area).quantize(Decimal('0.01')), floor or '', int(deposit), int(rent or 0)


def main():
    prod = {}
    with gzip.open(os.path.join(OUT, 'prod_baseline.csv.gz'), 'rt', encoding='utf-8') as f:
        for r in csv.reader(f):
            prod[key(*r[:5])] = r
    result = Counter()
    samples = {}
    with gzip.open(os.path.join(OUT, 'property_real.csv.gz'), 'rt', encoding='utf-8') as f:
        for r in csv.DictReader(f):
            p = prod.get(key(r['address'], r['area_sqm'], r['floor'], r['deposit'], r['monthly_rent']))
            if p is None:
                continue
            result['matched'] += 1
            checks = {
                'landlord_name': (r['landlord_name'], p[5]),
                'market_price': (r['market_price'], p[6]),
                'price_date': (r['price_date'], p[7]),
                'ledger_key': ((r['sigungu_code'], r['bjdong_code'], r['bun'], r['ji']), tuple(p[8:12])),
                'lat_lng_7': ('%.7f,%.7f' % (float(r['latitude']), float(r['longitude'])),
                              '%.7f,%.7f' % (float(p[12]), float(p[13]))),
            }
            for name, (mine, app) in checks.items():
                if mine == app:
                    result[name + '_same'] += 1
                else:
                    result[name + '_diff'] += 1
                    samples.setdefault(name, (r['address'], mine, app))
    print('baseline rows', len(prod))
    for k in sorted(result):
        print(k, result[k])
    for name, s in samples.items():
        print('diff sample', name, s)


if __name__ == '__main__':
    main()
