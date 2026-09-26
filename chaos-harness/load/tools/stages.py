import csv,gzip,sys,collections
# 단계별(워밍업 제외): 상태 코드 분포, 성공(2xx·3xx·의도한 4xx 제외 429) 처리량, 성공 요청 p95/p99, 지도 2단계 성공 p95
f=sys.argv[1]
st=collections.defaultdict(collections.Counter); dur=collections.defaultdict(list); m2=collections.defaultdict(list)
ts=collections.defaultdict(list)
for r in csv.DictReader(gzip.open(f,'rt')):
    if r['metric_name']!='http_req_duration': continue
    tags=dict(kv.split('=',1) for kv in r['extra_tags'].split('&') if '=' in kv)
    ph=tags.get('phase');
    if not ph or tags.get('warmup')!='false': continue
    s=r['status']; st[ph][s]+=1; ts[ph].append(int(r['timestamp']))
    if s and s[0] in '23':
        v=float(r['metric_value']); dur[ph].append(v)
        if tags.get('ep')=='map_s2': m2[ph].append(v)
def pct(a,p):
    if not a: return float('nan')
    a=sorted(a); return a[min(len(a)-1,int(len(a)*p))]
for ph in sorted(st):
    span=max(ts[ph])-min(ts[ph]) or 1; n=sum(st[ph].values()); ok=len(dur[ph])
    print(f"{ph:10} n={n:6} ok={ok:6} ok_rps={ok/span:6.1f} okp95={pct(dur[ph],.95):7.1f} okp99={pct(dur[ph],.99):8.1f} map2p95={pct(m2[ph],.95):7.1f} codes={dict(st[ph].most_common(6))}")
