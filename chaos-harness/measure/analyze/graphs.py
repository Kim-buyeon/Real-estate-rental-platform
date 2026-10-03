"""그래프 G1 ~ G7 — 양식 [10] 규칙: y 는 0 부터 · 계층 색 고정 · 캡션은 회차 번호만 · 워밍업 음영.

척도가 다른 두 값(지연 · TPS)은 한 축에 겹치지 않고 x 를 공유하는 위아래 판으로 나눈다(이중 y 축은 읽는 사람을 속인다).
"""
from __future__ import annotations

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402


LAYER = {"OS": "#5b6b7c", "앱": "#2e7d32", "경계": "#d9822b", "DB": "#1f4e79", "캐시": "#8e44ad", "입구": "#7f8c8d",
         "외부": "#a0522d", "설명 안 됨": "#c9c9c9"}
INK = "#1d232b"
MUTED = "#6b7480"
GRID = "#e3e7ec"
WARM = "#f1e6c8"


def _font():
    names = {f.name for f in font_manager.fontManager.ttflist}
    for n in ("Malgun Gothic", "AppleGothic", "NanumGothic", "Noto Sans CJK KR", "Noto Sans KR"):
        if n in names:
            return [n, "DejaVu Sans"]
    return ["DejaVu Sans"]


plt.rcParams.update({
    "font.family": _font(), "axes.unicode_minus": False, "font.size": 9,
    "axes.edgecolor": "#b9c2cc", "axes.labelcolor": INK, "xtick.color": MUTED, "ytick.color": MUTED,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.6, "axes.spines.top": False, "axes.spines.right": False,
    "legend.frameon": False, "legend.fontsize": 8, "figure.dpi": 110, "savefig.bbox": "tight",
})

# 같은 계층 안의 여러 선은 선 모양으로 가른다
STYLES = ["-", "--", ":", "-."]


def _shade(ax, warmup):
    if warmup:
        ax.axvspan(0, warmup, color=WARM, alpha=0.6, lw=0, zorder=0)


def _caption(fig, text):
    fig.text(0.01, 1.0, text, fontsize=8.5, color=MUTED, ha="left", va="bottom")


def g1(s, path):
    ts = (s.get("jtl") or {}).get("timeseries") or []
    if not ts:
        return None
    w = s["window"]["warmup_sec"] or 0
    x = [p["t"] for p in ts]
    nan = float("nan")
    thr = [p["threads"] if p["threads"] is not None else nan for p in ts]
    fig, axes = plt.subplots(4, 1, figsize=(8.2, 7.2), sharex=True, gridspec_kw={"height_ratios": [2.4, 1.3, 1, 1]})
    a, b, c, d = axes
    for key, ls, lw, col in (("p50", ":", 1.4, MUTED), ("p95", "-", 2.0, LAYER["DB"]), ("p99", "--", 1.4, MUTED)):
        a.plot(x, [p[key] if p[key] is not None else nan for p in ts], ls, color=col, lw=lw, label=key)
    a.axhline(500, color="#c62828", lw=1, alpha=0.7)
    a.text(x[-1], 500, "500 ms ", color="#c62828", fontsize=8, va="bottom", ha="right")
    a.set_ylabel("응답 시간 (ms)")
    a.legend(loc="upper left", ncol=3)
    b.plot(x, [p["tps"] for p in ts], color=LAYER["앱"], lw=2)
    b.set_ylabel("달성 TPS")
    c.plot(x, thr, color=LAYER["입구"], lw=1.6)
    c.set_ylabel("인가 스레드")
    d.plot(x, [p["error_rate"] for p in ts], color="#c62828", lw=1.6)
    d.set_ylabel("오류율 (%)")
    d.set_xlabel("경과 시간 (초, 10초 창)")
    for ax in axes:
        ax.set_ylim(bottom=0)
        _shade(ax, w)
    _caption(fig, f"G1 ({s['round']}) — 음영: 워밍업 {w}초")
    fig.savefig(path)
    plt.close(fig)
    return path


def _group_max(series, step=5):
    """같은 묶음(슬롯 넷 · 앱 노드 둘)은 시각별 최대 한 줄로 — 가장 먼저 찬 것이 보이면 된다."""
    acc = {}
    for pts in series:
        for t, v in pts:
            if v is None:
                continue
            k = round(t / step) * step
            acc[k] = max(acc.get(k, v), v)
    return sorted(acc.items())


def _smooth(pts, step=10):
    acc = {}
    for t, v in pts:
        acc.setdefault(int(t // step) * step, []).append(v)
    return [(k, sum(v) / len(v)) for k, v in sorted(acc.items())]


def g2(s, path):
    ts = (s.get("resources") or {}).get("timeseries") or {}
    wait = (s.get("db") or {}).get("wait_ts") or {}
    if not ts and not wait:
        return None
    groups = {}
    for name, d in ts.items():
        g = groups.setdefault((d["layer"], d.get("group", name)), [])
        g.append(d["points"])
    order = list(LAYER)
    fig, ax = plt.subplots(figsize=(8.2, 4.2))
    count = {}
    for (lay, grp), lst in sorted(groups.items(), key=lambda x: (order.index(x[0][0]) if x[0][0] in order else 9, x[0][1])):
        i = count.get(lay, 0)
        count[lay] = i + 1
        pts = _group_max(lst)
        ax.plot([p[0] for p in pts], [p[1] for p in pts], STYLES[i % 4], color=LAYER.get(lay, MUTED), lw=1.6, label=f"[{lay}] {grp}")
    for i, (node, pts) in enumerate(sorted(wait.items())):
        # 활성 세션 ÷ vCPU(2), 10초 평균 — 100% 면 CPU 수만큼 일하는 세션이 찼다
        sm = _smooth(pts)
        ax.plot([p[0] for p in sm], [min(100.0 * p[1] / 2, 100) for p in sm], STYLES[i % 4], color=LAYER["DB"], lw=1.6,
                label=f"[DB] {node} 활성 세션÷vCPU")
    ax.set_ylim(0, 105)
    ax.set_ylabel("사용률 (%)")
    ax.set_xlabel("경과 시간 (초) — 묶음 안 여러 대는 시각별 최대")
    _shade(ax, s["window"]["warmup_sec"] or 0)
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1), fontsize=7)
    lg = s.get("loadgen") or {}
    cap = f"G2 ({s['round']})"
    if lg:
        cap += f" — 부하 생성기 CPU 최대 {lg.get('cpu_max_pct')}%"
    _caption(fig, cap)
    fig.savefig(path)
    plt.close(fig)
    return path


def g3(s, base, path):
    if not base:
        return None
    cur = {e["label"]: e["p95"] for e in s.get("endpoints") or []}
    old = {e["label"]: e["p95"] for e in base.get("endpoints") or []}
    labels = [l for l in cur if l in old]
    if not labels:
        return None
    fig, ax = plt.subplots(figsize=(8.2, 0.45 * len(labels) + 1.4))
    y = range(len(labels))
    h = 0.36
    ax.barh([i + h / 2 for i in y], [old[l] or 0 for l in labels], height=h, color="#9fb2c6", label=f"전 ({base['round']})")
    ax.barh([i - h / 2 for i in y], [cur[l] or 0 for l in labels], height=h, color=LAYER["DB"], label=f"후 ({s['round']})")
    ax.axvline(500, color="#c62828", lw=1)
    ax.text(500, len(labels) - 0.4, " 목표 500 ms", color="#c62828", fontsize=8)
    ax.set_yticks(list(y))
    ax.set_yticklabels(labels)
    ax.invert_yaxis()
    ax.set_xlim(left=0)
    ax.set_xlabel("p95 (ms)")
    ax.legend(loc="lower right")
    _caption(fig, f"G3 ({base['round']} → {s['round']})")
    fig.savefig(path)
    plt.close(fig)
    return path


BUDGET_GROUP = {"입구": "입구", "앱": "앱", "경계": "경계", "DB": "DB", "캐시": "캐시", "외부": "외부"}


def g4(s, path, top=8):
    """엔드포인트마다 서버 구간이 p95 순위인 요청 하나를 구성 요소로 나눈 막대 — 합이 그 요청의 시간이다."""
    b = s.get("budget") or {}
    routes = [r for r, v in b.items() if v.get("server_ms")][:top]
    if not routes:
        return None
    fig, ax = plt.subplots(figsize=(8.2, 0.5 * len(routes) + 1.5))
    layers = ["앱", "경계", "DB", "캐시", "외부", "설명 안 됨"]
    left = [0.0] * len(routes)
    for lay in layers:
        vals = []
        for r in routes:
            v = b[r]
            if lay == "설명 안 됨":
                vals.append(v.get("unexplained_ms") or 0)
            else:
                vals.append(sum(x["ms"] or 0 for x in v["rows"] if x["layer"] == lay))
        if not any(vals):
            continue
        ax.barh(range(len(routes)), vals, left=left, color=LAYER[lay], edgecolor="white", linewidth=1, label=lay,
                hatch="//" if lay == "설명 안 됨" else None)
        left = [a + c for a, c in zip(left, vals)]
    ax.set_yticks(range(len(routes)))
    ax.set_yticklabels(routes)
    ax.invert_yaxis()
    ax.set_xlim(left=0)
    ax.set_xlabel("p95 순위 요청 하나의 구성 (ms) — 서버 구간 기준, 입구 · 큐는 [7.1] 표")
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1))
    _caption(fig, f"G4 ({s['round']})")
    fig.savefig(path)
    plt.close(fig)
    return path


def g5(contrib, path):
    """results/contrib.json — {"rounds": "R03 → R09", "items": [{"label": "...", "DB": 40, "앱": 10, "경계": 30, "분리 불가": 20}]}."""
    if not contrib or not contrib.get("items"):
        return None
    items = contrib["items"]
    fig, ax = plt.subplots(figsize=(8.2, 0.5 * len(items) + 1.4))
    cols = [("DB", LAYER["DB"]), ("앱", LAYER["앱"]), ("경계", LAYER["경계"]), ("분리 불가", "#c9c9c9")]
    left = [0.0] * len(items)
    for k, col in cols:
        vals = [it.get(k) or 0 for it in items]
        ax.barh(range(len(items)), vals, left=left, color=col, edgecolor="white", linewidth=1, label=k,
                hatch="//" if k == "분리 불가" else None)
        left = [a + b for a, b in zip(left, vals)]
    ax.set_yticks(range(len(items)))
    ax.set_yticklabels([it.get("label", "") for it in items])
    ax.invert_yaxis()
    ax.set_xlim(left=0)
    ax.set_xlabel("기여 (%)")
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1))
    _caption(fig, f"G5 ({contrib.get('rounds', '')})")
    fig.savefig(path)
    plt.close(fig)
    return path


def g6(summaries, sig, path):
    rows = [(n, s) for n, s in summaries if s and s.get("key")]
    if not rows:
        return None
    names = [s["round"] for _, s in rows]
    x = list(range(len(rows)))
    p95 = [s["key"].get("p95") for _, s in rows]
    tps = [s["key"].get("tps") for _, s in rows]
    fig, (a, b) = plt.subplots(2, 1, figsize=(8.2, 5), sharex=True)
    if sig and sig.get("threshold_pct") is not None:
        for ax, k in ((a, "p95"), (b, "tps")):
            med = ((sig.get("metrics") or {}).get(k) or {}).get("median")
            if med:
                band = med * sig["threshold_pct"] / 100
                ax.axhspan(med - band, med + band, color="#dfe7f0", lw=0, label=f"흔들림 띠 ±{sig['threshold_pct']}%")
    a.plot(x, [v if v is not None else float("nan") for v in p95], "-o", color=LAYER["DB"], lw=2, ms=5, label="p95 (ms)")
    b.plot(x, [v if v is not None else float("nan") for v in tps], "-o", color=LAYER["앱"], lw=2, ms=5, label="달성 TPS")
    for i, (_, s) in enumerate(rows):
        if (s.get("meta") or {}).get("changed"):
            for ax in (a, b):
                ax.axvline(i, color=LAYER["경계"], lw=1, ls="--", alpha=0.8)
    a.set_ylabel("p95 (ms)")
    b.set_ylabel("달성 TPS")
    for ax in (a, b):
        ax.set_ylim(bottom=0)
        ax.legend(loc="upper left", fontsize=7)
    b.set_xticks(x)
    b.set_xticklabels(names, rotation=0)
    _caption(fig, "G6 — 점선: 바꾼 것이 있는 회차")
    fig.savefig(path)
    plt.close(fig)
    return path


def g7(s, path):
    mx = s.get("matrix")
    if not mx:
        return None
    cols = mx["columns"]
    rows = list(mx["rows"].values())
    fig, ax = plt.subplots(figsize=(8.2, 0.45 * len(rows) + 1.6))
    import matplotlib.colors as mcolors
    cmap = mcolors.LinearSegmentedColormap.from_list("seq", ["#f4f7fb", LAYER["DB"]])
    for i, row in enumerate(rows):
        for j, c in enumerate(cols):
            v = row["shares"].get(c)
            if v is None:
                ax.add_patch(plt.Rectangle((j, i), 1, 1, facecolor="white", edgecolor="#c9d2dc", hatch="///", lw=0.5))
                continue
            ax.add_patch(plt.Rectangle((j, i), 1, 1, facecolor=cmap(min(v, 100) / 100), edgecolor="white", lw=2))
            ax.text(j + 0.5, i + 0.5, f"{v:.0f}%", ha="center", va="center", fontsize=8, color="white" if v > 55 else INK)
    ax.set_xlim(0, len(cols))
    ax.set_ylim(len(rows), 0)
    ax.set_xticks([j + 0.5 for j in range(len(cols))])
    ax.set_xticklabels(cols, rotation=25, ha="right")
    ax.set_yticks([i + 0.5 for i in range(len(rows))])
    ax.set_yticklabels([r["label"] for r in rows])
    ax.grid(False)
    for sp in ax.spines.values():
        sp.set_visible(False)
    _caption(fig, f"G7 ({s['round']}) — 행 합 100%, 빗금: 미측정")
    fig.savefig(path)
    plt.close(fig)
    return path


def render_all(s, base, summaries, sig, contrib, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    made = {}
    for key, fn in (("G1", lambda p: g1(s, p)), ("G2", lambda p: g2(s, p)), ("G3", lambda p: g3(s, base, p)),
                    ("G4", lambda p: g4(s, p)), ("G5", lambda p: g5(contrib, p)), ("G6", lambda p: g6(summaries, sig, p)),
                    ("G7", lambda p: g7(s, p))):
        p = out_dir / f"{key}.png"
        if p.exists():
            p.unlink()
        r = fn(p)
        made[key] = p.name if r else None
    return made
