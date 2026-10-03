"""서울시 전월세 파일 → 쓸 수 있는 아파트 · 오피스텔 행 (#376).

입력: <DATA_ROOT>/rent/rent_<연도>/*.csv (cp949, OA-21276)
출력: <DATA_ROOT>/out/rent_usable.csv.gz — 지번(대지) · 층 · 면적 · 보증금 · 계약일이 있는 행
     <DATA_ROOT>/out/buildings.csv.gz   — 건물(자치구코드 · 법정동코드 · 본번 · 부번)별 원주소 키
"""
import csv
import glob
import gzip
import io
import os
from collections import OrderedDict

from appcompat import jibun_text

DATA_ROOT = os.environ.get('LOADTEST_DATA', r'C:\Users\bu200\loadtest-data')
USAGE = {'아파트': 'APARTMENT', '오피스텔': 'OFFICETEL'}
FIELDS = ['year', 'sgg_code', 'sgg_name', 'bjd_code', 'dong', 'bon', 'bu', 'jibun', 'floor', 'contract_date',
          'area', 'deposit', 'rent', 'ptype', 'build_year', 'building_name']


def read_rows(path):
    raw = open(path, 'rb').read()
    try:
        text = raw.decode('cp949')
    except UnicodeDecodeError:
        text = raw.decode('utf-8-sig')
    reader = csv.reader(io.StringIO(text))
    header = next(reader)
    ix = {k: i for i, k in enumerate(header)}
    for row in reader:
        if len(row) < len(header):
            continue
        yield lambda k, r=row: r[ix[k]].strip()


def main():
    out_dir = os.path.join(DATA_ROOT, 'out')
    os.makedirs(out_dir, exist_ok=True)
    buildings = OrderedDict()
    kept = skipped = 0
    with gzip.open(os.path.join(out_dir, 'rent_usable.csv.gz'), 'wt', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(FIELDS)
        for year in range(2011, 2026):
            path = glob.glob(os.path.join(DATA_ROOT, 'rent', 'rent_%d' % year, '*'))[0]
            for g in read_rows(path):
                ptype = USAGE.get(g('건물용도'))
                floor, bon, area, deposit, cdate = g('층'), g('본번'), g('임대면적'), g('보증금(만원)'), g('계약일')
                # 산 지번은 실거래 API 표기를 확인하지 못해 뺀다(지번구분 대지만)
                if not ptype or not bon or not floor or not area or not deposit or len(cdate) != 8 \
                        or g('지번구분') != '대지':
                    skipped += 1
                    continue
                try:
                    floor_i = int(floor)
                    deposit_won = int(deposit.replace(',', '')) * 10_000
                    rent_won = int((g('임대료(만원)') or '0').replace(',', '')) * 10_000
                except ValueError:
                    skipped += 1
                    continue
                jibun = jibun_text(bon, g('부번'))
                bkey = (g('자치구코드'), g('법정동코드'), bon, g('부번') or '0000')
                if bkey not in buildings:
                    buildings[bkey] = (g('자치구명'), g('법정동명'), jibun)
                w.writerow([year, g('자치구코드'), g('자치구명'), g('법정동코드'), g('법정동명'), bon, g('부번') or '0000',
                            jibun, floor_i, cdate, area, deposit_won, rent_won, ptype, g('건축년도'), g('건물명')])
                kept += 1
            print(year, 'kept', kept, 'skipped', skipped, flush=True)
    with gzip.open(os.path.join(out_dir, 'buildings.csv.gz'), 'wt', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['sgg_code', 'bjd_code', 'bon', 'bu', 'sgg_name', 'dong', 'jibun'])
        for k, v in buildings.items():
            w.writerow(list(k) + list(v))
    print('rows', kept, 'buildings', len(buildings))


if __name__ == '__main__':
    main()
