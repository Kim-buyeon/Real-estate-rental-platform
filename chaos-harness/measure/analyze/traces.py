"""추적(OTLP JSON 줄 단위) — 요청 안의 SQL · 메서드 · Redis(store) · 외부 구간. [5.3] [6.2] [7.1] [7.11]."""
from __future__ import annotations

import json
import re
from collections import defaultdict
from pathlib import Path

from .util import percentile, mean, rnd, route_key, open_text

METHOD_SCOPE = "io.opentelemetry.methods"
PER_ROW_LIMIT = 100         # 한 추적 안에서 이보다 많이 불리면 행마다 불리는 메서드(메서드 목록 생성기와 같은 규칙)
SSE_ROUTE = "notifications/stream"   # 연결 시간이 응답 시간인 경로 — 통계에서 빼고 연결 수만 센다
# store 패키지 밖에서 Redis 를 쓰는 클래스 — 이 메서드 구간도 Redis 시간이다
REDIS_CLASSES = (
    "com.duri.rentalplatform.common.lock.DistributedLockAspect",
    "com.duri.rentalplatform.common.security.StreamTicketStore",
    "com.duri.rentalplatform.domain.notification.sender.SseNotificationSender",
    "com.duri.rentalplatform.external.buildingledger.BuildingLedgerDailyQuota",
    "com.duri.rentalplatform.external.buildingledger.BuildingLedgerRateLimiter",
)
TOP_METHODS = 3             # 시간 예산에 이름으로 세우는 메서드 수
# 비밀번호 대조(BCrypt) — 측정 모드 목록 생성기(tools/gen-methods.py THIRD_PARTY)가 이 클래스의 메서드를 구간으로 넣는다(#390).
# 구간 이름 · 속성 모양은 다른 메서드 구간과 같다(code.namespace = 선언한 클래스). 자식 구간이 없어 자기 시간 = 구간 길이.
# 실제 계측 메서드는 BCryptPasswordEncoder 의 protected matchesNonNull · encodeNonNullPassword 다 — matches · encode 는 상위
# AbstractValidatingPasswordEncoder 의 final 메서드라 구간이 붙지 않는다. 분류는 메서드 이름이 아니라 클래스(code.namespace)로
# 한다 — 상위 이름으로 잡히는 판이 와도 같은 BCrypt 로 센다
BCRYPT_NS = ("org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
             "org.springframework.security.crypto.password.AbstractValidatingPasswordEncoder")
STATEMENT_TOP = 30          # 문장별 실행 속도 표에 남기는 수(총 시간 순)
SQL_TEXT_MAX = 2000         # 추적 SQL 문장을 이 길이까지 남긴다 — JPA 문장은 열 목록이 길어 앞 수백 자가 같다(WHERE 가 뒤에 있다)
STMT_KEY_MAX = 1000         # 문장 키 길이 상한
STMT_PREFIX_MIN = 80        # 한쪽이 잘린 문장(질의 통계 상위 20 은 300자)을 앞부분으로 이을 때 겹쳐야 하는 최소 길이
KEEP_ATTRS = ("http.route", "url.path", "http.target", "http.request.method", "http.method", "url.full", "http.url",
              "http.response.status_code", "db.system", "db.system.name", "db.query.text", "db.statement",
              "db.namespace", "db.name", "db.operation", "db.operation.name", "server.address", "net.peer.name",
              "code.function", "code.namespace", "code.function.name")
_KIND = {"SPAN_KIND_INTERNAL": 1, "SPAN_KIND_SERVER": 2, "SPAN_KIND_CLIENT": 3, "SPAN_KIND_PRODUCER": 4, "SPAN_KIND_CONSUMER": 5}


class Span:
    __slots__ = ("tid", "sid", "pid", "name", "kind", "s", "e", "a", "scope", "inst", "err")

    @property
    def dur(self):
        return self.e - self.s


def _val(v):
    if not isinstance(v, dict):
        return v
    for k in ("stringValue", "intValue", "doubleValue", "boolValue"):
        if k in v:
            x = v[k]
            if k == "intValue":
                try:
                    return int(x)
                except (TypeError, ValueError):
                    return x
            return x
    if "arrayValue" in v:
        return [_val(x) for x in v["arrayValue"].get("values", [])]
    return None


def _attrs(lst, keep=KEEP_ATTRS):
    out = {}
    for a in lst or []:
        k = a.get("key")
        if keep is None or k in keep:
            out[k] = _val(a.get("value"))
    return out


def load(path: Path, t_lo_ms=None, t_hi_ms=None):
    """회차 구간(ms) 안에서 시작한 구간만 남긴다."""
    spans = []
    texts = {}
    seen = set()     # (traceId, spanId) — 로컬 구성은 내보내기 둘이 같은 수신기로 가서 구간이 두 번 온다. 운영에는 없지만 해가 없다
    with open_text(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                req = json.loads(line)
            except json.JSONDecodeError:
                continue
            for rs in req.get("resourceSpans", []):
                res = _attrs((rs.get("resource") or {}).get("attributes"), keep=None)
                inst = res.get("service.instance.id") or res.get("host.name") or "?"
                for ss in rs.get("scopeSpans", rs.get("instrumentationLibrarySpans", [])):
                    scope = (ss.get("scope") or ss.get("instrumentationLibrary") or {}).get("name", "")
                    for sp in ss.get("spans", []):
                        s = Span()
                        s.s = int(sp.get("startTimeUnixNano", 0)) / 1e6
                        s.e = int(sp.get("endTimeUnixNano", 0)) / 1e6
                        if t_lo_ms is not None and s.s < t_lo_ms:
                            continue
                        if t_hi_ms is not None and s.s > t_hi_ms:
                            continue
                        s.tid = sp.get("traceId")
                        s.sid = sp.get("spanId")
                        if (s.tid, s.sid) in seen:
                            continue
                        seen.add((s.tid, s.sid))
                        s.pid = sp.get("parentSpanId") or None
                        s.name = sp.get("name", "")
                        k = sp.get("kind", 1)
                        s.kind = _KIND.get(k, k) if isinstance(k, str) else int(k)
                        s.a = _attrs(sp.get("attributes"))
                        for qk in ("db.query.text", "db.statement"):
                            if isinstance(s.a.get(qk), str):
                                # 같은 문장이 수십만 번 온다 — 한 객체로 묶어 메모리를 문장 종류 수만큼만 쓴다
                                t = s.a[qk][:SQL_TEXT_MAX]
                                s.a[qk] = texts.setdefault(t, t)
                        s.scope = scope
                        s.inst = inst
                        s.err = (sp.get("status") or {}).get("code") in (2, "STATUS_CODE_ERROR")
                        spans.append(s)
    return spans


# ---------------------------------------------------------------- 분류

def is_db(s):
    return s.kind == 3 and any(k.startswith("db.") for k in s.a)


def is_txn_end(s):
    op = str(s.a.get("db.operation.name") or s.a.get("db.operation") or "").upper()
    nm = s.name.upper()
    return op in ("COMMIT", "ROLLBACK") or nm.startswith("COMMIT") or nm.startswith("ROLLBACK")


def is_method(s):
    # SQL · 외부 호출 구간은 범위 이름과 상관없이 메서드로 세지 않는다(겹쳐 세지 않게)
    if is_db(s) or is_external(s):
        return False
    return s.scope == METHOD_SCOPE or (s.kind == 1 and ("code.function" in s.a or "code.function.name" in s.a))


def namespace(s):
    ns = s.a.get("code.namespace")
    if ns:
        return ns
    fn = s.a.get("code.function.name")
    return fn.rsplit(".", 1)[0] if fn and "." in fn else ""


LOCK_ASPECT = "com.duri.rentalplatform.common.lock.DistributedLockAspect"


def is_lock_advice(s):
    """DistributedLockAspect.lock — @Around 라 잠긴 업무 메서드 전체(SQL 포함)를 감싼다. Redis 구간이 아니라
    보통 메서드로 센다(Redis 몫은 그 안의 acquire · release · Renewal.run 이 갖는다)."""
    return namespace(s).split("$", 1)[0] == LOCK_ASPECT and s.name.rsplit(".", 1)[-1] == "lock"


def is_store(s):
    """Redis 를 쓰는 메서드 구간 — store 패키지 또는 REDIS_CLASSES(중첩 클래스 Outer$Inner 는 Outer 로 본다).
    Redis 시간은 이 구간들의 자기 시간 합이다 — RedisTemplate 호출은 계측되지 않아 자기 시간 ≈ Redis 왕복."""
    if not is_method(s) or is_lock_advice(s):
        return False
    ns = namespace(s)
    return ".store." in ns or ns.split("$", 1)[0] in REDIS_CLASSES


def is_bcrypt(s):
    return is_method(s) and namespace(s).split("$", 1)[0] in BCRYPT_NS


def is_external(s):
    return s.kind == 3 and not is_db(s) and any(k in s.a for k in ("http.request.method", "http.method", "url.full", "http.url"))


def db_node(s):
    return str(s.a.get("server.address") or s.a.get("net.peer.name") or "?")


_KEY_CACHE = {}


def stmt_key(text):
    """SQL 문장 정규화 — 질의 통계($n)와 추적(?)을 같은 키로. 리터럴 · 숫자도 ? 로(질의 통계는 리터럴도 $n 이다).
    길이는 STMT_KEY_MAX 까지 — 앞 100자로 자르면 열 목록이 같은 JPA 문장들(WHERE 만 다르다)이 한 키로 섞인다."""
    if not text:
        return None
    k = _KEY_CACHE.get(text)
    if k is None:
        t = re.sub(r"\$\d+", "?", str(text))
        t = re.sub(r"'(?:[^']|'')*'", "?", t)
        t = re.sub(r"\b\d+(\.\d+)?\b", "?", t)
        t = re.sub(r"\s+", " ", t).strip().lower()
        # 기호 앞뒤 공백을 지운다 — 사람이 쓴 질의 파일(「a = ?, b」)과 ORM 이 만든 문장(「a=?,b」)이 같은 키가 되게
        t = re.sub(r" ?([^\w\s]) ?", r"\1", t)
        k = t[:STMT_KEY_MAX]
        if len(_KEY_CACHE) < 100_000:
            _KEY_CACHE[text] = k
    return k


def match_key(key, keys):
    """문장 키 하나를 다른 원천의 키 집합에서 찾는다 — 같으면 그것, 아니면 한쪽이 다른 쪽의 앞부분(잘린 문장)이고
    겹친 길이가 STMT_PREFIX_MIN 이상인 후보가 **하나뿐일 때만** 그것. 둘 이상이면 고르지 않는다(다른 문장을 잇지 않게)."""
    if not key:
        return None
    if key in keys:
        return key
    cand = [k for k in keys if k and min(len(k), len(key)) >= STMT_PREFIX_MIN and (k.startswith(key) or key.startswith(k))]
    return cand[0] if len(cand) == 1 else None


def stmt_of(s):
    return s.a.get("db.query.text") or s.a.get("db.statement") or s.name


def union_ms(intervals, lo=None, hi=None):
    """구간 합집합 길이 — 겹친 자식을 두 번 세지 않는다."""
    iv = []
    for a, b in intervals:
        if lo is not None:
            a = max(a, lo)
        if hi is not None:
            b = min(b, hi)
        if b > a:
            iv.append((a, b))
    iv.sort()
    tot = 0.0
    cur_a = cur_b = None
    for a, b in iv:
        if cur_b is None or a > cur_b:
            if cur_b is not None:
                tot += cur_b - cur_a
            cur_a, cur_b = a, b
        else:
            cur_b = max(cur_b, b)
    if cur_b is not None:
        tot += cur_b - cur_a
    return tot


def self_time(span, children):
    """자기 시간 = 구간 길이 − 자식 구간 합집합(자기 구간 안으로 자른 것)."""
    return max(span.dur - union_ms([(c.s, c.e) for c in children], span.s, span.e), 0.0)


# ---------------------------------------------------------------- 요청 하나

def _descendants(root, kids):
    out = []
    stack = list(kids.get(root.sid, []))
    while stack:
        c = stack.pop()
        out.append(c)
        stack.extend(kids.get(c.sid, []))
    return out


def request_profile(root, kids):
    desc = _descendants(root, kids)
    sql = [d for d in desc if is_db(d)]
    sql_q = [d for d in sql if not is_txn_end(d)]
    ends = sorted([d for d in sql if is_txn_end(d)], key=lambda x: x.s)
    store = [d for d in desc if is_store(d)]
    ext = [d for d in desc if is_external(d)]
    methods = [d for d in desc if is_method(d)]
    p = {
        "dur": root.dur,
        "sql_n": len(sql_q),
        "sql_ms": union_ms([(d.s, d.e) for d in sql]),
        "sql_max_ms": max((d.dur for d in sql_q), default=0.0),
        "commit_n": len(ends),
        "store_n": len(store),
        # 자기 시간 합 — 구간 길이(합집합)로 재면 감싼 업무 메서드 · SQL 까지 Redis 로 센다
        "store_ms": sum(self_time(d, kids.get(d.sid, [])) for d in store),
        "ext_ms": union_ms([(d.s, d.e) for d in ext]),
        "ext": [(str(d.a.get("server.address") or route_key(d.a.get("url.full")) or d.name), d.dur, d.err) for d in ext],
        "server_self_ms": self_time(root, kids.get(root.sid, [])),
        "db_nodes": defaultdict(lambda: [0, 0.0]),
        "method_self": defaultdict(float),
        "method_n": defaultdict(int),
        "stmt_n": defaultdict(int),
        "txns": [],
        "store_names": {d.name for d in store},
        # 요청 하나 안의 메서드 · 기타 구간 자기 시간 — 시간 예산 분해에 쓴다
        "other_self_ms": sum(self_time(d, kids.get(d.sid, [])) for d in desc
                             if not is_db(d) and not is_store(d) and not is_external(d) and not is_method(d)),
        # BCrypt 메서드 구간의 자기 시간 — method_self 안에도 들어 있다(단계별 분해가 따로 뗀다)
        "bcrypt_ms": sum(self_time(d, kids.get(d.sid, [])) for d in methods if is_bcrypt(d)),
        "t": root.s / 1000.0,                   # 요청 시작(유닉스 초) — 단계 나누기
        "sql_list": [],                         # (문장 키, 길이 ms, DB 노드) — 문장별 실행 속도
        "stmt_text": {},
    }
    for d in sql_q:
        n = p["db_nodes"][db_node(d)]
        n[0] += 1
        n[1] += d.dur
        k = stmt_key(stmt_of(d))
        p["stmt_n"][k] += 1
        p["sql_list"].append((k, d.dur, db_node(d)))
        p["stmt_text"].setdefault(k, stmt_of(d))
    for m in methods:
        if is_store(m):
            continue
        p["method_self"][m.name] += self_time(m, kids.get(m.sid, []))
    for m in methods:
        p["method_n"][m.name] += 1
    # 트랜잭션 — 커밋(롤백) 하나가 하나를 닫는다. 시작은 직전 끝 뒤의 첫 SQL
    sql_sorted = sorted(sql, key=lambda x: x.s)
    prev_end = None
    for e in ends:
        members = [d for d in sql_sorted if d.e <= e.e and (prev_end is None or d.s >= prev_end)]
        if members:
            t0 = members[0].s
            hold = e.e - t0
            gap = max(hold - union_ms([(d.s, d.e) for d in members], t0, e.e), 0.0)
            p["txns"].append((hold, gap))
        prev_end = e.e
    return p


# ---------------------------------------------------------------- 묶음

def _dist(vals):
    return {"mean": rnd(mean(vals), 3), "p50": rnd(percentile(vals, 50), 3), "p95": rnd(percentile(vals, 95), 3),
            "max": rnd(max(vals), 3) if vals else None}


def statements(profiles, top=STATEMENT_TOP):
    """문장 키별 실행 속도 — 호출 수 · p50 · p95 · 평균 · 요청당 횟수 · DB 노드별 호출 · 부르는 경로. 총 시간 순.
    요청당 횟수 = 호출 수 ÷ 그 문장을 한 번이라도 부른 요청 수."""
    d = defaultdict(lambda: {"durs": [], "nodes": defaultdict(int), "reqs": 0, "routes": defaultdict(int), "text": None})
    for p in profiles:
        seen = set()
        for k, dur, node in p["sql_list"]:
            if not k:
                continue
            x = d[k]
            x["durs"].append(dur)
            x["nodes"][node] += 1
            x["routes"][p.get("route")] += 1
            if x["text"] is None:
                x["text"] = p["stmt_text"].get(k)
            seen.add(k)
        for k in seen:
            d[k]["reqs"] += 1
    rows = []
    for k, x in d.items():
        v = x["durs"]
        rows.append({"key": k, "text": (x["text"] or "")[:160], "calls": len(v), "requests": x["reqs"],
                     "per_request": rnd(len(v) / x["reqs"], 3) if x["reqs"] else None,
                     "total_ms": rnd(sum(v), 1), "mean_ms": rnd(mean(v), 3),
                     "p50_ms": rnd(percentile(v, 50), 3), "p95_ms": rnd(percentile(v, 95), 3),
                     "p95_usable": len(v) >= 100,
                     "db_nodes": dict(x["nodes"]),
                     "routes": [r for r, _ in sorted(x["routes"].items(), key=lambda kv: -kv[1]) if r][:3]})
    rows.sort(key=lambda r: -r["total_ms"])
    return rows[:top]


def analyze(path: Path, win, collect=None):
    """collect — 리스트를 주면 요청마다의 분해(request_profile + route)를 담아 돌려준다(단계별 분해 · steps.py)."""
    if not path.exists():
        # 집계 뒤 회차 스크립트가 spans.jsonl 을 gzip 한다 — 다시 집계할 때는 압축본을 읽는다
        gz = path.with_name(path.name + ".gz")
        if not gz.exists():
            return None
        path = gz
    lo = win.lo * 1000 if win.lo is not None else None
    hi = (win.hi + 60) * 1000 if win.hi is not None else None
    spans = load(path, (win.start * 1000) if win.start is not None else None, hi)
    if not spans:
        return {"spans": 0}
    by_trace = defaultdict(list)
    for s in spans:
        by_trace[s.tid].append(s)
    routes = defaultdict(list)
    slots = defaultdict(list)
    bg = defaultdict(list)
    db_split = defaultdict(lambda: [0, 0.0])
    slow = []
    sse = 0
    for tid, ss in by_trace.items():
        ids = {s.sid for s in ss}
        kids = defaultdict(list)
        for s in ss:
            if s.pid and s.pid in ids:
                kids[s.pid].append(s)
        for s in ss:
            parent_in = s.pid and s.pid in ids
            if s.kind == 2 and (lo is None or s.s >= lo) and (win.hi is None or s.s <= win.hi * 1000):
                r = route_key(s.a.get("http.route") or s.a.get("url.path") or s.a.get("http.target") or s.name.split(" ")[-1])
                if r == SSE_ROUTE:
                    sse += 1
                    continue
                p = request_profile(s, kids)
                p["tid"] = tid
                p["route"] = r
                routes[r].append(p)
                slots[s.inst].append(s.dur)
                for node, (n, ms) in p["db_nodes"].items():
                    db_split[node][0] += n
                    db_split[node][1] += ms
                slow.append((s.dur, tid, r, p))
            elif not parent_in and s.kind != 2 and (lo is None or s.s >= lo):
                # 요청 밖 — 배치 · 스케줄 · 외부 호출
                bg[s.name].append(request_profile(s, kids))
    route_out = {}
    for r, ps in sorted(routes.items(), key=lambda x: -len(x[1])):
        n = len(ps)
        mself = defaultdict(float)
        mcnt = defaultdict(int)
        for p in ps:
            for k, v in p["method_self"].items():
                mself[k] += v
            for k, v in p["method_n"].items():
                mcnt[k] += v
        server_total = sum(p["dur"] for p in ps)
        top_methods = sorted(mself.items(), key=lambda x: -x[1])[:10]
        stmt_max = defaultdict(int)
        for p in ps:
            for k, v in p["stmt_n"].items():
                stmt_max[k] = max(stmt_max[k], v)
        rep = sorted(stmt_max.items(), key=lambda x: -x[1])[:3]
        holds = [h for p in ps for h, _ in p["txns"]]
        gaps = [g for p in ps for _, g in p["txns"]]
        comp = {"server": [p["dur"] for p in ps], "sql": [p["sql_ms"] for p in ps],
                "redis": [p["store_ms"] for p in ps], "external": [p["ext_ms"] for p in ps]}
        named = [k for k, _ in top_methods[:TOP_METHODS]]
        route_out[r] = {
            "requests": n,
            "server_ms": _dist(comp["server"]),
            "server_ms_sum": rnd(server_total, 1),
            "sql_per_request": _dist([p["sql_n"] for p in ps]),
            "sql_n_sum": sum(p["sql_n"] for p in ps),
            "db_ms_per_request": _dist(comp["sql"]),
            "db_ms_sum": rnd(sum(comp["sql"]), 1),
            "redis_calls_per_request": rnd(sum(p["store_n"] for p in ps) / n, 3),
            "redis_calls_sum": sum(p["store_n"] for p in ps),
            "redis_ms_per_request": _dist(comp["redis"]),
            "external_ms_per_request": _dist(comp["external"]),
            "txn_hold_ms": _dist(holds) if holds else None,
            "txn_gap_ms": _dist(gaps) if gaps else None,
            "txn_hold_ms_sum": rnd(sum(holds), 1),
            "top_methods": [{"method": k, "self_ms_sum": rnd(v, 1), "share_pct": rnd(100.0 * v / server_total, 1) if server_total else None,
                             "spans_per_request": rnd(mcnt[k] / n, 2)} for k, v in top_methods],
            "per_row_methods": sorted({k for p in ps for k, v in p["method_n"].items() if v > PER_ROW_LIMIT}),
            "repeated_statements": [{"statement": k, "max_per_request": v} for k, v in rep if v > 1],
            "decomp": decompose(ps, named),
        }
    slow.sort(key=lambda x: -x[0])
    slow_out = []
    for dur, tid, r, p in slow[:3]:
        parts = {"SQL": p["sql_ms"], "Redis": p["store_ms"], "외부": p["ext_ms"], "요청 구간 자체": p["server_self_ms"]}
        parts.update({f"메서드 {k}": v for k, v in p["method_self"].items()})
        big = max(parts.items(), key=lambda x: x[1])
        slow_out.append({"trace_id": tid, "route": r, "ms": rnd(dur, 1), "largest": big[0], "largest_ms": rnd(big[1], 1)})
    ext_hosts = defaultdict(list)
    for ps in list(routes.values()) + list(bg.values()):
        for p in ps:
            for host, d, err in p["ext"]:
                ext_hosts[host].append((d, err))
    total_sql = sum(n for n, _ in db_split.values())
    allp = [p for ps in routes.values() for p in ps]
    if collect is not None:
        collect.extend(allp)
    return {
        "statements": statements(allp),
        "spans": len(spans),
        "traces": len(by_trace),
        "server_requests": sum(len(v) for v in routes.values()),
        "routes": route_out,
        "slots": {k: {"requests": len(v), "p95_ms": rnd(percentile(v, 95), 2)} for k, v in sorted(slots.items())},
        "db_split": {k: {"queries": n, "ms": rnd(ms, 1), "share_pct": rnd(100.0 * n / total_sql, 1) if total_sql else None}
                     for k, (n, ms) in sorted(db_split.items())},
        "background": {k: {"count": len(ps), "ms": _dist([p["dur"] for p in ps]),
                           "sql_per_run": rnd(mean([p["sql_n"] for p in ps]), 2),
                           "db_ms_per_run": rnd(mean([p["sql_ms"] for p in ps]), 2)} for k, ps in sorted(bg.items())},
        "external": {h: {"count": len(v), "p95_ms": rnd(percentile([d for d, _ in v], 95), 1),
                         "error_rate_pct": rnd(100.0 * sum(1 for _, e in v if e) / len(v), 2)} for h, v in ext_hosts.items()},
        "slowest": slow_out,
        "sse_connections": sse,
        "statement_routes": _stmt_routes(routes),
    }


DECOMP_PARTS = [  # (키, 계층, 이름, 원천)
    ("db", "DB", "질의 실행 (SQL 구간 합집합)", "추적 SQL"),
    ("redis", "캐시", "Redis 왕복 (Redis 메서드 자기 시간)", "store · Redis 클래스 메서드 구간의 자기 시간"),
    ("external", "외부", "외부 호출", "추적 CLIENT"),
    ("methods_other", "앱", "그 밖의 메서드 자기 시간", "메서드 구간"),
    ("other_spans", "앱", "기타 계측 구간 자기 시간 (spring-data 저장소 · tomcat 내부 등)", "메서드 · SQL · 외부가 아닌 구간"),
    # 서버 구간 안 · 계측 구간 밖의 시간(Controller · 필터 · 직렬화 · 계측 안 된 코드)은 구성 요소로 세지 않는다 —
    # 양식 [7.1] 의 「설명되지 않은 시간」이 바로 이것이고, 5% 를 넘으면 계측 공백으로 본다(#378 재검토)
]


def _parts(p, named):
    """요청 하나 → 구성 요소별 시간. 서로 겹치지 않게 나눈 값이라 합이 요청 시간을 넘지 않는다
    (넘으면 자식 구간이 동시에 돈 것 — overlap 으로 남긴다)."""
    d = {"db": p["sql_ms"], "redis": p["store_ms"], "external": p["ext_ms"], "other_spans": p["other_self_ms"]}
    for k in named:
        d["method:" + k] = p["method_self"].get(k, 0.0)
    d["methods_other"] = sum(v for k, v in p["method_self"].items() if k not in named)
    tot = sum(d.values())
    d["unexplained"] = max(p["dur"] - tot, 0.0)
    d["overlap"] = max(tot - p["dur"], 0.0)
    return d


def decompose(ps, named):
    """[7.1] — 구간 p95 를 따로 구해 더하지 않는다. 요청마다 분해한 뒤 (가) 구성 요소 평균 (나) 서버 구간이
    p95 순위인 실제 요청 하나의 분해를 낸다."""
    parts = [_parts(p, named) for p in ps]
    keys = list(parts[0].keys()) if parts else []
    order = sorted(range(len(ps)), key=lambda i: ps[i]["dur"])
    idx = order[min(len(order) - 1, max(0, int(round(0.95 * (len(order) - 1)))))] if order else None
    return {
        "named_methods": named,
        "mean": {k: rnd(sum(x[k] for x in parts) / len(parts), 3) for k in keys} | {"server": rnd(mean([p["dur"] for p in ps]), 3)},
        "p95_request": ({k: rnd(v, 3) for k, v in parts[idx].items()} | {"server": rnd(ps[idx]["dur"], 3), "trace_id": ps[idx].get("tid")})
        if idx is not None else None,
    }


def _stmt_routes(routes):
    """정규화한 문장 → 부르는 엔드포인트(호출 수 순). [4.1] 「엔드포인트」 칸."""
    m = defaultdict(lambda: defaultdict(int))
    for r, ps in routes.items():
        for p in ps:
            for k, v in p["stmt_n"].items():
                if k:
                    m[k][r] += v
    return {k: [r for r, _ in sorted(v.items(), key=lambda x: -x[1])] for k, v in m.items()}
