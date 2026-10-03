"""추적(OTLP JSON 줄 단위) — 요청 안의 SQL · 메서드 · Redis(store) · 외부 구간. [5.3] [6.2] [7.1] [7.11]."""
from __future__ import annotations

import json
import re
from collections import defaultdict
from pathlib import Path

from .util import percentile, mean, rnd, route_key, open_text

METHOD_SCOPE = "io.opentelemetry.methods"
PER_ROW_LIMIT = 50          # 요청당 구간이 이보다 많으면 행마다 불리는 메서드로 본다
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
                        s.pid = sp.get("parentSpanId") or None
                        s.name = sp.get("name", "")
                        k = sp.get("kind", 1)
                        s.kind = _KIND.get(k, k) if isinstance(k, str) else int(k)
                        s.a = _attrs(sp.get("attributes"))
                        for qk in ("db.query.text", "db.statement"):
                            if isinstance(s.a.get(qk), str) and len(s.a[qk]) > 300:
                                s.a[qk] = s.a[qk][:300]
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
    return s.scope == METHOD_SCOPE or (s.kind == 1 and ("code.function" in s.a or "code.function.name" in s.a))


def namespace(s):
    ns = s.a.get("code.namespace")
    if ns:
        return ns
    fn = s.a.get("code.function.name")
    return fn.rsplit(".", 1)[0] if fn and "." in fn else ""


def is_store(s):
    return is_method(s) and ".store." in namespace(s)


def is_external(s):
    return s.kind == 3 and not is_db(s) and any(k in s.a for k in ("http.request.method", "http.method", "url.full", "http.url"))


def db_node(s):
    return str(s.a.get("server.address") or s.a.get("net.peer.name") or "?")


def stmt_key(text):
    """SQL 문장 정규화 — 질의 통계($n)와 추적(?)을 같은 키로."""
    if not text:
        return None
    t = re.sub(r"\$\d+", "?", str(text))
    t = re.sub(r"'(?:[^']|'')*'", "?", t)
    t = re.sub(r"\b\d+(\.\d+)?\b", "?", t)
    t = re.sub(r"\s+", " ", t).strip().lower()
    return t[:100]


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
        "store_ms": union_ms([(d.s, d.e) for d in store]),
        "ext_ms": union_ms([(d.s, d.e) for d in ext]),
        "ext": [(str(d.a.get("server.address") or route_key(d.a.get("url.full")) or d.name), d.dur, d.err) for d in ext],
        "server_self_ms": self_time(root, kids.get(root.sid, [])),
        "db_nodes": defaultdict(lambda: [0, 0.0]),
        "method_self": defaultdict(float),
        "method_n": defaultdict(int),
        "stmt_n": defaultdict(int),
        "txns": [],
        "store_names": {d.name for d in store},
    }
    for d in sql_q:
        n = p["db_nodes"][db_node(d)]
        n[0] += 1
        n[1] += d.dur
        p["stmt_n"][stmt_key(stmt_of(d))] += 1
    for m in methods:
        p["method_self"][m.name] += self_time(m, kids.get(m.sid, []))
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


def analyze(path: Path, win):
    if not path.exists():
        return None
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
                p = request_profile(s, kids)
                p["tid"] = tid
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
        comp = {
            "server": [p["dur"] for p in ps], "sql": [p["sql_ms"] for p in ps],
            "sql_main": [p["sql_max_ms"] for p in ps], "sql_extra": [max(p["sql_ms"] - p["sql_max_ms"], 0) for p in ps],
            "redis": [p["store_ms"] for p in ps], "external": [p["ext_ms"] for p in ps],
            "server_self": [p["server_self_ms"] for p in ps],
        }
        # 시간 예산의 Service 줄 — store(Redis) 메서드는 캐시 줄이 따로 갖는다
        store_names = set().union(*[p["store_names"] for p in ps])
        for name, _ in [m for m in top_methods if m[0] not in store_names][:3]:
            comp["method:" + name] = [p["method_self"].get(name, 0.0) for p in ps]
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
            "per_row_methods": sorted([k for k, v in mcnt.items() if v / n > PER_ROW_LIMIT]),
            "repeated_statements": [{"statement": k, "max_per_request": v} for k, v in rep if v > 1],
            "budget_p95": {k: rnd(percentile(v, 95), 3) for k, v in comp.items()},
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
    return {
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
        "statement_routes": _stmt_routes(routes),
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
