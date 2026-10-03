"""건물별 주소 정규화 · 좌표 (#376). 앱 RealAddressNormalizeClient · RealGeocodeClient 와 같은 호출 · 같은 첫 건 선택.

입력: out/buildings.csv.gz   출력: out/address_cache.jsonl (이어 받기 — 이미 있는 건물은 건너뛴다)
한 줄: {"key": [sgg, bjd, bon, bu], "raw": 원주소, "road": roadAddr, "adm": admCd, "lat": y, "lng": x, "err": ...}
키는 저장소 루트 .env 의 ADDRESS_API_KEY · KAKAO_REST_API_KEY.
"""
import csv
import gzip
import json
import os
import threading
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from appcompat import raw_address

DATA_ROOT = os.environ.get('LOADTEST_DATA', r'C:\Users\bu200\loadtest-data')
ENV = os.path.join(os.path.dirname(__file__), '..', '..', '..', '.env')
OUT = os.path.join(DATA_ROOT, 'out', 'address_cache.jsonl')


def env(name):
    for line in open(ENV, encoding='utf-8'):
        if line.startswith(name + '='):
            return line.split('=', 1)[1].strip().strip('"')
    raise KeyError(name)


JUSO_KEY, KAKAO_KEY = env('ADDRESS_API_KEY'), env('KAKAO_REST_API_KEY')


def get_json(url, headers=None):
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers=headers or {})
            return json.loads(urllib.request.urlopen(req, timeout=15).read().decode('utf-8'))
        except Exception as e:  # 재시도 — 앱도 Retry 를 건다
            last = e
            time.sleep(1.5 * (attempt + 1))
    raise last


def resolve(row):
    sgg, bjd, bon, bu, sgg_name, dong, jibun = row
    raw = raw_address(sgg_name, dong, jibun)
    result = {'key': [sgg, bjd, bon, bu], 'raw': raw}
    try:
        q = urllib.parse.urlencode({'confmKey': JUSO_KEY, 'keyword': raw, 'currentPage': 1, 'countPerPage': 1,
                                    'resultType': 'json'})
        body = get_json('https://business.juso.go.kr/addrlink/addrLinkApi.do?' + q)
        common = body['results']['common']
        if common.get('errorCode') != '0':
            result['err'] = 'juso:' + common.get('errorCode', '') + ':' + common.get('errorMessage', '')
            return result
        found = body['results'].get('juso') or []
        if not found:
            result['err'] = 'address_not_found'
            return result
        result['road'], result['adm'] = found[0]['roadAddr'], found[0]['admCd']
        q = urllib.parse.urlencode({'query': result['road']})
        doc = get_json('https://dapi.kakao.com/v2/local/search/address.json?' + q,
                       {'Authorization': 'KakaoAK ' + KAKAO_KEY}).get('documents') or []
        if not doc or doc[0].get('x') is None or doc[0].get('y') is None:
            result['err'] = 'coordinates_not_found'
            return result
        result['lat'], result['lng'] = doc[0]['y'], doc[0]['x']
    except Exception as e:
        result['err'] = 'exception:%r' % e
    return result


def main():
    done = set()
    if os.path.exists(OUT):
        for line in open(OUT, encoding='utf-8'):
            r = json.loads(line)
            if not r.get('err', '').startswith('exception'):
                done.add(tuple(r['key']))
    with gzip.open(os.path.join(DATA_ROOT, 'out', 'buildings.csv.gz'), 'rt', encoding='utf-8') as f:
        rows = [r for r in list(csv.reader(f))[1:] if tuple(r[:4]) not in done]
    print('todo', len(rows), 'done', len(done), flush=True)
    lock = threading.Lock()
    count = 0
    with open(OUT, 'a', encoding='utf-8') as out, ThreadPoolExecutor(6) as pool:
        for result in pool.map(resolve, rows):
            with lock:
                out.write(json.dumps(result, ensure_ascii=False) + '\n')
                count += 1
                if count % 500 == 0:
                    out.flush()
                    print(count, flush=True)
    print('finished', count)


if __name__ == '__main__':
    main()
