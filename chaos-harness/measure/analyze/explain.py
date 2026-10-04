"""실제 값 EXPLAIN · 스냅샷 — [4.2] 일곱 줄 · [4.3] 사용 인덱스 · [6.1] 형 변환 · S4 · S5 · S6, auto_explain 대조.

입력: results/<회차>/explain/
    queries/<파일>.yaml 질의 정의(explain.sh 가 받은 파일들의 사본 — login.yaml · endpoints.yaml …) — 아래 load_queries 의 형식.
                      예전 모양 queries.yaml 하나도 읽는다. 여러 파일의 id 는 서로 겹치면 안 된다
    <id>.cold.txt     EXPLAIN (ANALYZE, BUFFERS, SETTINGS) 첫 번째 실행(콜드)
    <id>.txt          세 번째 실행(마지막)
    <id>.skipped      건너뛴 질의와 사유(쓰기인데 --allow-write 가 없을 때)
    <id>.csv          스냅샷(EXPLAIN 없이 그대로 실행) — COPY … CSV HEADER
    run.json          explain.sh 실행 정보(local · allow_write · utc)
명령(explain.sh 가 부른다 — 측정 폴더에서):
    python -m analyze.explain split <출력 폴더> <질의 파일> [<질의 파일> …]   질의 · 스냅샷 SQL 을 파일로 쪼개고 목록을 한 줄씩 낸다
"""
from __future__ import annotations

import csv
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

from . import db
from .traces import stmt_key
from .util import read_json, rnd

ID_RE = re.compile(r"^[A-Za-z0-9_.-]+$")
ERROR_FACTOR_LIMIT = 10      # 추정 오차가 이 배를 넘으면 통계가 계획을 그르친 것(관측 설계서 3.4 S6)


# ---------------------------------------------------------------- 질의 파일

def _scalar(v):
    v = v.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
        return v[1:-1].replace("''", "'") if v[0] == "'" else v[1:-1]
    # 따옴표 밖의 「 #」 뒤는 주석
    m = re.search(r"\s+#", v)
    return v[:m.start()].rstrip() if m else v


def load_queries(path: Path):
    """질의 파일 → {"queries": [...], "snapshots": [...]}. JSON 이면 그대로, 아니면 아래 모양만 읽는 최소 YAML 파서
    (PyYAML 이 없는 부하 생성기에서도 돈다).

        queries:
          - id: login_find_auth
            source: UserAuthRepository.findByAuthTypeAndProviderId (UserCommandService.java:111)
            endpoint: auth/login   # 선택 — 엔드포인트 키(route_key 와 같은 모양). [4.1] 「엔드포인트」 · [5.3]
            kind: read        # read | write
            node: primary     # primary | replica
            sql: |
              SELECT ...
        snapshots:
          - id: user_auth_rows
            node: primary     # 생략하면 primary
            sql: |
              SELECT ...

    블록 문자열(| · >)은 그 키보다 깊게 들여쓴 줄을 모은다. 흐름 표기([] · {}) · 앵커는 읽지 않는다."""
    text = Path(path).read_text(encoding="utf-8-sig")
    if text.lstrip().startswith("{"):
        return json.loads(text)
    out = {}
    section = None
    item = None
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        raw = lines[i]
        i += 1
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        ind = len(raw) - len(raw.lstrip(" "))
        s = raw.strip()
        if ind == 0:
            key = s.split(":", 1)[0].strip()
            section = out.setdefault(key, [])
            item = None
            continue
        if s.startswith("- "):
            item = {}
            section.append(item)
            s = s[2:].strip()
            ind = ind + 2
        if item is None or ":" not in s:
            raise ValueError(f"질의 파일을 읽지 못했다({path}:{i}): {raw!r}")
        key, _, val = s.partition(":")
        key, val = key.strip(), val.strip()
        if val[:1] in ("|", ">"):
            fold = val[0] == ">"
            body = []
            while i < len(lines):
                nxt = lines[i]
                nind = len(nxt) - len(nxt.lstrip(" "))
                if nxt.strip() and nind <= ind:
                    break
                body.append(nxt)
                i += 1
            nonempty = [b for b in body if b.strip()]
            cut = min((len(b) - len(b.lstrip(" ")) for b in nonempty), default=0)
            body = [b[cut:] for b in body]
            item[key] = (" ".join(b.strip() for b in body if b.strip()) if fold else "\n".join(body)).strip("\n")
        else:
            item[key] = _scalar(val)
    return out


def check_queries(q):
    """id · kind · node 를 확인하고 기본값을 채운다. 문제가 있으면 ValueError."""
    seen = set()
    for kind_key in ("queries", "snapshots"):
        for x in q.get(kind_key) or []:
            if not ID_RE.match(str(x.get("id", ""))):
                raise ValueError(f"id 가 올바르지 않다(영문 · 숫자 · _ . -): {x.get('id')!r}")
            if x["id"] in seen:
                raise ValueError(f"id 가 겹친다: {x['id']}")
            seen.add(x["id"])
            if not (x.get("sql") or "").strip():
                raise ValueError(f"{x['id']}: sql 이 비었다")
            x.setdefault("node", "primary")
            if x["node"] not in ("primary", "replica"):
                raise ValueError(f"{x['id']}: node 는 primary | replica")
            if kind_key == "queries":
                x.setdefault("kind", "read")
                if x["kind"] not in ("read", "write"):
                    raise ValueError(f"{x['id']}: kind 는 read | write")
                if x["kind"] == "write" and x["node"] == "replica":
                    raise ValueError(f"{x['id']}: standby(replica)에는 쓸 수 없다")
    return q


def clean_sql(sql):
    """끝의 세미콜론 · 공백을 뗀다 — EXPLAIN 과 COPY ( … ) 안에 그대로 넣는다."""
    return re.sub(r"[\s;]+$", "", sql.strip())


def load_many(paths):
    """질의 파일 여럿 → 하나로 합친다(queries · snapshots 를 이어 붙인다). id 겹침은 check_queries 가 잡는다."""
    out = {"queries": [], "snapshots": []}
    for p in paths:
        q = load_queries(p)
        for k in ("queries", "snapshots"):
            out[k] += q.get(k) or []
    return out


def split(paths, outdir):
    """질의 파일(하나 또는 여럿) → <출력>/q-<id>.sql · s-<id>.sql, 목록 줄 「query|snapshot <탭> id <탭> kind <탭> node」."""
    q = check_queries(load_many([paths] if isinstance(paths, (str, Path)) else paths))
    od = Path(outdir)
    od.mkdir(parents=True, exist_ok=True)
    lines = []
    for x in q.get("queries") or []:
        (od / f"q-{x['id']}.sql").write_text(clean_sql(x["sql"]) + "\n", encoding="utf-8", newline="\n")
        lines.append(f"query\t{x['id']}\t{x['kind']}\t{x['node']}")
    for x in q.get("snapshots") or []:
        (od / f"s-{x['id']}.sql").write_text(clean_sql(x["sql"]) + "\n", encoding="utf-8", newline="\n")
        lines.append(f"snapshot\t{x['id']}\tread\t{x['node']}")
    return lines


# ---------------------------------------------------------------- 계획 파서

_NODE = re.compile(
    r"^(?P<pre>\s*(?:->\s*)?)(?P<name>[A-Z][A-Za-z ]*?)(?: using (?P<idx>\S+))?(?: on (?P<rel>\S+)(?: (?P<alias>\S+))?)?"
    r"\s+\(cost=[\d.]+\.\.[\d.]+ rows=(?P<rows>\d+) width=\d+\)"
    r"(?: \(actual (?:time=[\d.]+\.\.(?P<t2>[\d.]+) )?rows=(?P<arows>[\d.]+) loops=(?P<loops>\d+)\)| \((?P<never>never executed)\))?")
_BUF = re.compile(r"Buffers:(.*)")
_SORT = re.compile(r"Sort Method: (?P<m>.+?)\s+(?P<kind>Memory|Disk): (?P<kb>\d+)kB")
# 열 쪽 형 변환 — 「(열)::형」. 값 쪽('EMAIL'::text)은 걸리지 않는다. 형 이름은 여러 낱말일 수 있다(character varying 등)
_CAST = re.compile(r"\((?P<col>[a-z_][\w.]*)\)::(?P<type>(?:character varying|double precision|timestamp with(?:out)? time zone"
                   r"|time with(?:out)? time zone|bit varying|[a-z_]\w*)(?:\[\])?)")


def _bufs(s):
    d = {}
    for scope, rest in re.findall(r"(shared|local|temp)((?:\s+\w+=\d+)+)", s):
        for k, v in re.findall(r"(\w+)=(\d+)", rest):
            d[f"{scope}_{k}"] = int(v)
    return d


def parse_plan(text):
    """EXPLAIN (ANALYZE, BUFFERS, SETTINGS) 글 출력 → 노드 목록과 [4.2] 일곱 줄 재료. psql -At 의 한 줄 한 행 그대로 받는다.
    auto_explain 블록(log_timing off 라 actual 에 time 이 없다)도 같은 함수로 읽는다."""
    nodes = []
    root_bufs = None
    seen_child = False
    temp = {"read": 0, "written": 0}
    sorts = []
    casts = []
    planning = execution = None
    settings = None
    for ln in text.splitlines():
        s = ln.rstrip()
        st = s.strip()
        if not st:
            continue
        if st.startswith("Planning Time:"):
            planning = float(re.findall(r"[\d.]+", st)[0])
            continue
        if st.startswith("Execution Time:"):
            execution = float(re.findall(r"[\d.]+", st)[0])
            continue
        if st.startswith("Settings:"):
            settings = st[len("Settings:"):].strip()
            continue
        if st.startswith("Planning:"):
            seen_child = True       # 이 뒤의 Buffers 는 계획 단계 몫
            continue
        m = _NODE.match(s)
        if m:
            if nodes:
                seen_child = True
            name = m["name"].strip()
            idx, rel = m["idx"], m["rel"]
            if name in ("Bitmap Index Scan",) and rel and not idx:
                idx, rel = rel, None
            nodes.append({"name": name, "index": idx, "rel": rel, "est_rows": int(m["rows"]),
                          "act_rows": float(m["arows"]) if m["arows"] is not None else None,
                          "loops": int(m["loops"]) if m["loops"] else None, "never": bool(m["never"]),
                          "time_ms": float(m["t2"]) if m["t2"] else None})
            continue
        b = _BUF.search(st)
        if b:
            d = _bufs(b.group(1))
            temp["read"] = max(temp["read"], d.get("temp_read", 0))
            temp["written"] = max(temp["written"], d.get("temp_written", 0))
            if nodes and not seen_child and root_bufs is None:
                root_bufs = d
            continue
        so = _SORT.search(st)
        if so:
            sorts.append({"method": so["m"].strip(), "kind": so["kind"], "kb": int(so["kb"])})
        if re.match(r"^(Index Cond|Filter|Recheck Cond|Hash Cond|Join Filter|Merge Cond|Cache Key):", st):
            for c in _CAST.finditer(st):
                e = {"column": c["col"], "type": c["type"].strip()}
                if e not in casts:
                    casts.append(e)
    return {"nodes": nodes, "root_buffers": root_bufs, "temp": temp, "sorts": sorts, "casts": casts,
            "planning_ms": planning, "execution_ms": execution, "settings": settings}


def _access(n):
    tgt = " ".join(x for x in (f"({n['index']})" if n["index"] else None, f"on {n['rel']}" if n["rel"] else None) if x)
    return f"{n['name']} {tgt}".strip()


def access_signature(plan):
    """접근 방법의 모양 — 스캔 노드 (종류, 인덱스 또는 표) 집합. auto_explain 의 실제 계획과 견준다."""
    return sorted({(n["name"], n["index"] or n["rel"]) for n in plan["nodes"] if "Scan" in n["name"]})


def seven(plan):
    """[4.2] 일곱 줄 + 추정 오차 상세 · 형 변환 · 인덱스."""
    nodes = plan["nodes"]
    if not nodes:
        return None
    root = nodes[0]
    scans = [_access(n) for n in nodes if "Scan" in n["name"]]
    joins = [n["name"] for n in nodes if "Join" in n["name"] or n["name"].startswith("Nested Loop")]
    worst = None
    for n in nodes:
        if n["act_rows"] is None or n["never"]:
            continue
        e, a = max(n["est_rows"], 1), max(n["act_rows"], 1)
        f = max(e / a, a / e)
        if worst is None or f > worst[0]:
            worst = (f, n)
    rb = plan["root_buffers"] or {}
    sorts = " · ".join(f"{s['method']} {s['kind']} {s['kb']}kB" for s in plan["sorts"])
    temp = plan["temp"]
    idx = []
    for n in nodes:
        if n["index"] and n["index"] not in idx:
            idx.append(n["index"])
    return {
        "access": scans or ["(스캔 없음)"],
        "join": joins or ["없음"],
        "est_rows": root["est_rows"], "act_rows": root["act_rows"], "loops": root["loops"],
        "error_factor": rnd(worst[0], 2) if worst else None,
        "error_node": _access(worst[1]) if worst else None,
        "error_over_limit": bool(worst and worst[0] > ERROR_FACTOR_LIMIT),
        "blocks_hit": rb.get("shared_hit", 0) if plan["root_buffers"] is not None else None,
        "blocks_read": rb.get("shared_read", 0) if plan["root_buffers"] is not None else None,
        "sort": sorts or "없음",
        "temp_blocks": temp["read"] + temp["written"],
        "disk_sort": any(s["kind"] == "Disk" for s in plan["sorts"]) or bool(temp["read"] or temp["written"]),
        "execution_ms": plan["execution_ms"], "planning_ms": plan["planning_ms"],
        "casts": plan["casts"],
        "indexes": idx,
        "settings": plan["settings"],
        "signature": access_signature(plan),
    }


# ---------------------------------------------------------------- auto_explain 대조

def auto_explain_match(db_dir: Path, key, signature):
    """같은 문장(Query Text 를 문장 키로 정규화)의 실제 계획 건수와 그중 EXPLAIN 과 접근 방법이 같은 건수 — 노드별."""
    out = {}
    for node, fn in (("db-01", "db01"), ("db-02", "db02")):
        p = db_dir / f"{fn}-auto-explain.log"
        if not p.exists():
            continue
        plans = same = 0
        other = defaultdict(int)
        for _, body in db.auto_explain_blocks(p):
            qt = db.query_text(body)
            if not qt or stmt_key(qt) != key:
                continue
            plans += 1
            sig = access_signature(parse_plan(body))
            if sig == signature:
                same += 1
            else:
                other[" + ".join(f"{a} {b or ''}".strip() for a, b in sig) or "(스캔 없음)"] += 1
        out[node] = {"plans": plans, "same_access": same, "other": dict(other)}
    return out


# ---------------------------------------------------------------- 스냅샷

def read_snapshot(path: Path):
    with open(path, encoding="utf-8", errors="replace", newline="") as f:
        rows = list(csv.reader(f))
    if not rows:
        return {"columns": [], "rows": []}
    return {"columns": rows[0], "rows": rows[1:]}


def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def s4_of(snap):
    """S4 — 통계상 행 수 vs 실제 행 수. 열 이름이 reltuples|estimated 와 count|actual 이면 첫 행으로 잰다."""
    cols = [c.lower() for c in snap["columns"]]
    ei = next((i for i, c in enumerate(cols) if c in ("reltuples", "estimated")), None)
    ai = next((i for i, c in enumerate(cols) if c in ("count", "actual")), None)
    if ei is None or ai is None or not snap["rows"]:
        return None
    e, a = _num(snap["rows"][0][ei]), _num(snap["rows"][0][ai])
    if e is None or a is None:
        return None
    return {"estimated": e, "actual": a, "ratio": rnd(e / a, 3) if a else None,
            "diff_pct": rnd(100.0 * (e - a) / a, 2) if a else None}


# ---------------------------------------------------------------- 묶음

def analyze(rdir: Path):
    ed = rdir / "explain"
    if not ed.exists():
        return None
    qfiles = sorted((ed / "queries").glob("*.yaml")) if (ed / "queries").exists() else []
    if (ed / "queries.yaml").exists():
        qfiles.append(ed / "queries.yaml")
    try:
        q = check_queries(load_many(qfiles)) if qfiles else {}
    except ValueError as e:
        q = {}
        err = str(e)
    else:
        err = None
    run = read_json(ed / "run.json", {}) or {}
    queries = []
    known = {x["id"]: x for x in q.get("queries") or []}
    ids = list(known) + sorted({p.name[:-len(".txt")] for p in ed.glob("*.txt") if not p.name.endswith(".cold.txt")} - set(known))
    for qid in ids:
        meta = known.get(qid, {})
        warm_p, cold_p, skip_p = ed / f"{qid}.txt", ed / f"{qid}.cold.txt", ed / f"{qid}.skipped"
        item = {"id": qid, "source": meta.get("source"), "kind": meta.get("kind"), "node": meta.get("node"),
                "endpoint": meta.get("endpoint"),
                "sql": clean_sql(meta["sql"]) if meta.get("sql") else None,
                "key": stmt_key(clean_sql(meta["sql"])) if meta.get("sql") else None,
                "skipped": skip_p.read_text(encoding="utf-8", errors="replace").strip() if skip_p.exists() else None,
                "error": (ed / f"{qid}.error").read_text(encoding="utf-8", errors="replace").strip()[:300] if (ed / f"{qid}.error").exists() else None,
                "warm": None, "cold": None, "auto_explain": None, "auto_explain_plans": None, "auto_explain_same": None}
        if warm_p.exists():
            item["warm"] = seven(parse_plan(warm_p.read_text(encoding="utf-8", errors="replace")))
        if cold_p.exists():
            item["cold"] = seven(parse_plan(cold_p.read_text(encoding="utf-8", errors="replace")))
        w = item.get("warm")
        if w and item["key"]:
            am = auto_explain_match(rdir / "db", item["key"], w["signature"])
            item["auto_explain"] = am
            item["auto_explain_plans"] = sum(v["plans"] for v in am.values()) if am else None
            item["auto_explain_same"] = sum(v["same_access"] for v in am.values()) if am else None
        queries.append(item)
    snaps, s4, s5 = [], [], []
    smeta = {x["id"]: x for x in q.get("snapshots") or []}
    for p in sorted(ed.glob("*.csv")):
        sid = p.stem
        sn = read_snapshot(p)
        sn["id"] = sid
        sn["rows"] = sn["rows"][:20]
        snaps.append(sn)
        v = s4_of(sn)
        if v:
            s4.append(dict(v, id=sid))
        else:
            s5.append({"id": sid, "columns": sn["columns"], "rows": sn["rows"], "source": (smeta.get(sid) or {}).get("source")})
    ws = [x["warm"] for x in queries if x.get("warm")]
    return {
        "local": run.get("local"), "allow_write": run.get("allow_write"), "captured_utc": run.get("utc"),
        "queries_error": err,
        "queries": queries,
        "snapshots": snaps, "s4": s4, "s5": s5,
        "error_over_limit": sum(1 for w in ws if w["error_over_limit"]),
        "disk_sorts": sum(1 for w in ws if w["disk_sort"]),
        "casts": sorted({f"({c['column']})::{c['type']}" for w in ws for c in w["casts"]}),
        "indexes": sorted({i for w in ws for i in w["indexes"]}),
        "auto_explain_plans": sum(x.get("auto_explain_plans") or 0 for x in queries) if any(x.get("auto_explain") for x in queries) else None,
        "auto_explain_same": sum(x.get("auto_explain_same") or 0 for x in queries) if any(x.get("auto_explain") for x in queries) else None,
    }


def main(argv):
    if len(argv) >= 3 and argv[0] == "split":
        try:
            for line in split(argv[2:], argv[1]):
                print(line)
        except (ValueError, OSError) as e:
            print(f"질의 파일 오류: {e}", file=sys.stderr)
            return 1
        return 0
    print("사용: python -m analyze.explain split <출력 폴더> <질의 파일> [<질의 파일> …]", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
