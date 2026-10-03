# 서울 25개 구 아파트·오피스텔 매매 실거래를 월 단위로 받아 원본 XML 그대로 저장한다.
# 이미 받은 파일은 건너뛰므로 끊기면 다시 실행하면 된다. 키는 저장소 .env 에서 읽는다.
import os, re, sys, time, urllib.request

ROOT = os.environ.get('LOADTEST_DATA', r'C:\Users\bu200\loadtest-data')
OUT = os.path.join(ROOT, 'sale')
ENV = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', '.env')
KEY = next(l.split('=', 1)[1].strip().strip('"') for l in open(ENV, encoding='utf-8') if l.startswith('DATA_GO_KR_API_KEY='))
APIS = {'apt': 'RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev',
        'offi': 'RTMSDataSvcOffiTrade/getRTMSDataSvcOffiTrade'}
GU = ['11110','11140','11170','11200','11215','11230','11260','11290','11305','11320','11350','11380','11410',
      '11440','11470','11500','11530','11545','11560','11590','11620','11650','11680','11710','11740']
MONTHS = [f'{y}{m:02d}' for y in range(2010, 2027) for m in range(1, 13) if f'{y}{m:02d}' <= '202609']

def get(url):
    for i in range(5):
        try:
            return urllib.request.urlopen(url, timeout=60).read().decode('utf-8')
        except Exception as e:
            time.sleep(2 * (i + 1))
    raise RuntimeError(url[:120])

calls = 0
for kind, path in APIS.items():
    for gu in GU:
        d = os.path.join(OUT, kind, gu); os.makedirs(d, exist_ok=True)
        for ym in MONTHS:
            page = 1
            while True:
                f = os.path.join(d, f'{ym}_{page}.xml')
                if os.path.exists(f):
                    body = open(f, encoding='utf-8').read()
                else:
                    body = get(f'https://apis.data.go.kr/1613000/{path}?serviceKey={KEY}&LAWD_CD={gu}&DEAL_YMD={ym}&numOfRows=1000&pageNo={page}')
                    calls += 1
                    code = re.search(r'<resultCode>([^<]*)', body)
                    if not code or code.group(1) not in ('000', '00'):
                        print('STOP', kind, gu, ym, page, body[:300], flush=True); sys.exit(1)
                    open(f, 'w', encoding='utf-8').write(body)
                total = int((re.search(r'<totalCount>(\d+)', body) or [0, 0])[1])
                if page * 1000 >= total: break
                page += 1
        print(kind, gu, 'done, calls so far', calls, flush=True)
print('ALL DONE calls', calls)
