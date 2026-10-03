"""그 밖의 원자료 — Redis commandstats · 크레딧 · 프로파일러 · 부하 생성기 vmstat."""
from __future__ import annotations

import re
from collections import defaultdict
from pathlib import Path

from .util import read_json, rnd, parse_time


# ---------------------------------------------------------------- Redis commandstats

def parse_commandstats(text):
    """`cmdstat_get:calls=1,usec=2,usec_per_call=2.0,...` → {cmd: {calls, usec, ...}}."""
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("cmdstat_"):
            continue
        name, _, rest = line.partition(":")
        d = {}
        for kv in rest.split(","):
            k, _, v = kv.partition("=")
            try:
                d[k] = float(v)
            except ValueError:
                pass
        out[name[len("cmdstat_"):]] = d
    return out


# 측정 수단(redis_exporter 긁기 · 관리 명령)의 몫 — 앱 명령에서 뺀다
ADMIN_COMMANDS = {"info", "config", "client", "slowlog", "latency", "memory", "dbsize", "ping", "command", "select",
                  "cluster", "auth", "hello"}


def _base_cmd(c):
    """`config|get` · `client|list` 처럼 하위 명령이 붙은 이름은 앞부분으로."""
    return c.split("|", 1)[0].lower()


def commandstats_delta(pre_path: Path, post_path: Path, requests=None):
    if not pre_path.exists() or not post_path.exists():
        return None
    pre = parse_commandstats(pre_path.read_text(encoding="utf-8", errors="replace"))
    post = parse_commandstats(post_path.read_text(encoding="utf-8", errors="replace"))
    cmds = {}
    for c, d in post.items():
        p = pre.get(c, {})
        calls = d.get("calls", 0) - p.get("calls", 0)
        usec = d.get("usec", 0) - p.get("usec", 0)
        if calls < 0:   # 그 사이 재시작 — 뒤 값만 쓴다
            calls, usec = d.get("calls", 0), d.get("usec", 0)
        if calls <= 0:
            continue
        cmds[c] = {"calls": int(calls), "usec": int(usec), "usec_per_call": rnd(usec / calls, 3),
                   "failed": int(d.get("failed_calls", 0) - p.get("failed_calls", 0)),
                   "rejected": int(d.get("rejected_calls", 0) - p.get("rejected_calls", 0))}
    admin = {c: v for c, v in cmds.items() if _base_cmd(c) in ADMIN_COMMANDS}
    app = {c: v for c, v in cmds.items() if c not in admin}
    total = sum(v["calls"] for v in app.values())
    for v in cmds.values():
        v["calls_per_request"] = rnd(v["calls"] / requests, 4) if requests else None
    return {
        "total_calls": total,                     # 앱 명령만
        "total_usec": sum(v["usec"] for v in app.values()),
        "calls_per_request": rnd(total / requests, 4) if requests else None,
        "commands": dict(sorted(app.items(), key=lambda x: -x[1]["calls"])),
        "admin_calls": sum(v["calls"] for v in admin.values()),   # 측정 수단 몫
        "admin_commands": dict(sorted(admin.items(), key=lambda x: -x[1]["calls"])),
        "note": "회차 전후 차이(워밍업 포함). 요청 수는 워밍업을 뺀 도구 요청 수라 요청당 값이 약간 부풀 수 있다",
    }


# ---------------------------------------------------------------- 크레딧

CREDIT_KEYS = ("cpu_credit_balance", "cpu_surplus_balance", "cpu_surplus_charged", "ebs_burst_balance")


def credits(pre_path: Path, post_path: Path):
    pre = read_json(pre_path)
    post = read_json(post_path)
    if pre is None and post is None:
        return None
    nodes = sorted(set((pre or {}).get("nodes", {}).keys()) | set((post or {}).get("nodes", {}).keys()))
    out = {}
    for n in nodes:
        a = ((pre or {}).get("nodes") or {}).get(n) or {}
        b = ((post or {}).get("nodes") or {}).get(n) or {}
        out[n] = {}
        for k in CREDIT_KEYS:
            va, vb = a.get(k), b.get(k)
            out[n][k] = {"pre": va, "post": vb,
                         "delta": rnd(vb - va, 3) if isinstance(va, (int, float)) and isinstance(vb, (int, float)) else None}
    ebs_pre = [v["ebs_burst_balance"]["pre"] for k, v in out.items() if k.upper().startswith("DB") and v["ebs_burst_balance"]["pre"] is not None]
    ebs_post = [v["ebs_burst_balance"]["post"] for k, v in out.items() if v["ebs_burst_balance"]["post"] is not None]
    return {"pre_ts": (pre or {}).get("ts"), "post_ts": (post or {}).get("ts"), "nodes": out,
            "db_ebs_pre_min": min(ebs_pre) if ebs_pre else None, "ebs_post_min": min(ebs_post) if ebs_post else None}


# ---------------------------------------------------------------- 프로파일러

JIT_RE = re.compile(r"CompileBroker|C2Compiler|C1Compiler|C[12] CompilerThread|Compile::")
APP_RE = re.compile(r"com[./]duri[./]")
# [5.2] 고정 행 — 이름이 걸리는 프레임 패턴. 정해지지 않은 행은 None(사람이 채운다)
CATEGORIES = {
    "bcrypt": re.compile(r"BCrypt"),
    "leak_detection": re.compile(r"ProxyLeakTask|LeakTask"),
    "jit": JIT_RE,
    "code_conversion": None,
    "serialization": re.compile(r"jackson[./]"),
}


def _norm(frame):
    return frame.replace("/", ".")


def collapsed(path: Path):
    total = 0
    self_ = defaultdict(int)
    tot = defaultdict(int)
    cats = defaultdict(int)
    jit = 0
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.rstrip()
        if not line:
            continue
        stack, _, cnt = line.rpartition(" ")
        try:
            c = int(cnt)
        except ValueError:
            continue
        frames = [_norm(f) for f in stack.split(";")]
        total += c
        if any(JIT_RE.search(f) for f in frames):
            jit += c
        for k, rx in CATEGORIES.items():
            if rx is not None and any(rx.search(f) for f in frames):
                cats[k] += c
        # 앱 프레임 — 자기(맨 위 앱 프레임) · 전체(스택에 한 번)
        app = [f for f in frames if APP_RE.search(f)]
        if app:
            self_[app[-1]] += c
            for f in set(app):
                tot[f] += c
    pct = lambda v: rnd(100.0 * v / total, 2) if total else None
    return {
        "samples": total,
        "jit_share_pct": pct(jit),
        "app_reliable": (100.0 * jit / total < 40) if total else None,
        "categories_pct": {k: (pct(cats.get(k, 0)) if CATEGORIES[k] is not None else None) for k in CATEGORIES},
        "top_self": [{"method": k, "samples": v, "pct": pct(v)} for k, v in sorted(self_.items(), key=lambda x: -x[1])[:15]],
        "top_total": [{"method": k, "samples": v, "pct": pct(v)} for k, v in sorted(tot.items(), key=lambda x: -x[1])[:15]],
    }


def profiler(dir_: Path):
    if not dir_.exists():
        return None
    out = {}
    for f in sorted(dir_.glob("*.collapsed")):
        out[f.stem] = collapsed(f)
    return out or None


# ---------------------------------------------------------------- 부하 생성기

def loadgen_vmstat(path: Path, win):
    """`vmstat -t 1` 또는 `vmstat 1` 출력 — CPU 사용률(100 − id) 최대 · 평균. 시각이 없으면 전체."""
    if not path.exists():
        return None
    head = None
    seen_first = False
    vals = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split()
        if not parts:
            continue
        if "id" in parts and "us" in parts:
            head = parts
            continue
        if head is None or not parts[0].isdigit():
            continue
        if not seen_first:
            # 첫 데이터 줄은 부팅 뒤 평균이다 — 늘 버린다
            seen_first = True
            continue
        try:
            idle = float(parts[head.index("id")])
        except (ValueError, IndexError):
            continue
        # -t 이면 끝 두 칸이 날짜 · 시각, 머리 끝 칸이 시간대(UTC · KST)
        if win is not None and len(parts) >= 2 and re.match(r"\d{4}-\d{2}-\d{2}$", parts[-2]):
            tz = {"KST": "+09:00"}.get(head[-1], "+00:00")
            try:
                t = parse_time(parts[-2] + "T" + parts[-1] + tz)
                if not win.contains(t):
                    continue
            except ValueError:
                pass
        vals.append(100.0 - idle)
    if not vals:
        return None
    return {"cpu_max_pct": rnd(max(vals), 1), "cpu_mean_pct": rnd(sum(vals) / len(vals), 1), "samples": len(vals)}
