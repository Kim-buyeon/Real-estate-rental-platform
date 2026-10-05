"""알림 상태 이력 — 회차 구간에 Grafana 관리형 알림 규칙이 어떻게 움직였나. [7.8] 「알림이 울렸나」 · [9.4](라).

입력: results/<회차>/alerts.json (node/alerts.sh 가 쓴다 — 응답을 손대지 않고 담은 봉투)
    {"captured_utc", "from_ms", "to_ms", "round_start_ms", "round_end_ms",
     "history": <GET /api/v1/rules/history 응답 | null>,        "history_error": <문구 | null>,
     "annotations": <GET /api/annotations?type=alert 응답 | null>, "annotations_error": <문구 | null>,
     "rules": <GET /api/v1/provisioning/alert-rules 응답 | null>, "rules_error": <문구 | null>}
없으면 None — 보고서 칸은 「미측정」.

상태 이력의 모양(alerts.sh 머리 주석이 근거)
    history      데이터 프레임 JSON — {"schema": {"fields": [{"name": "time"}, {"name": "line"}, …]}, "data": {"values": [[…], […]]}}.
                 line 은 상태 전환 하나(previous · current · ruleUID · ruleTitle · labels …) — 객체 또는 JSON 글
    annotations  [{"time": ms, "prevState", "newState", "alertName" | "text", "alertId" | "ruleUID"?, …}]
둘 다 있으면 history 를 쓴다(규칙 UID · 제목이 줄마다 있다).
"""
from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

from .util import MEASURE_DIR, read_json, rnd

RULES_GLOB = "infra/grafana/alerting/rules-*.json"     # 저장소의 규칙 정의(관측 설계서 5.1 의 열셋)
FIRING = ("Alerting",)                                  # 「울렸다」로 세는 상태 — Pending 은 아직 대기(for 5분) 중이다


def _line(v):
    if isinstance(v, dict):
        return v
    if isinstance(v, str):
        try:
            x = json.loads(v)
            return x if isinstance(x, dict) else None
        except json.JSONDecodeError:
            return None
    return None


def _ms(t):
    """시각 — 밀리초 epoch 로. 초 · 나노초가 와도 크기로 가른다."""
    try:
        t = float(t)
    except (TypeError, ValueError):
        return None
    if t > 1e17:        # 나노초
        return t / 1e6
    if t < 1e11:        # 초
        return t * 1000
    return t


def from_history(h):
    """데이터 프레임 → [{"time_ms", "rule_uid", "rule_title", "from", "to", "labels"}]."""
    if not isinstance(h, dict):
        return []
    frames = h.get("frames") or ([h] if "schema" in h else [])
    if not frames and isinstance(h.get("results"), dict):
        frames = [f for r in h["results"].values() for f in (r.get("frames") or [])]
    out = []
    for fr in frames:
        fields = [f.get("name") for f in ((fr.get("schema") or {}).get("fields") or [])]
        vals = (fr.get("data") or {}).get("values") or []
        if "time" not in fields or "line" not in fields:
            continue
        ti, li = fields.index("time"), fields.index("line")
        lab_i = fields.index("labels") if "labels" in fields else None
        for k, t in enumerate(vals[ti] if ti < len(vals) else []):
            ln = _line(vals[li][k]) if li < len(vals) and k < len(vals[li]) else None
            if not ln:
                continue
            labels = ln.get("labels") or ln.get("instanceLabels") or {}
            if lab_i is not None and lab_i < len(vals) and k < len(vals[lab_i]) and isinstance(vals[lab_i][k], dict):
                labels = {**vals[lab_i][k], **labels}
            out.append({"time_ms": _ms(t), "rule_uid": ln.get("ruleUID") or labels.get("__alert_rule_uid__"),
                        "rule_title": ln.get("ruleTitle") or labels.get("alertname"),
                        "from": ln.get("previous"), "to": ln.get("current"),
                        "labels": {k_: v for k_, v in labels.items() if not str(k_).startswith("__")}})
    return out


def from_annotations(a):
    """주석 API → 같은 모양. 규칙 제목은 alertName, 없으면 text 의 첫 줄."""
    if not isinstance(a, list):
        return []
    out = []
    for x in a:
        if not isinstance(x, dict) or not (x.get("newState") or x.get("prevState")):
            continue
        title = x.get("alertName") or (str(x.get("text") or "").split("\n", 1)[0] or None)
        out.append({"time_ms": _ms(x.get("time")), "rule_uid": x.get("ruleUID") or x.get("alertUID"),
                    "rule_title": title, "from": x.get("prevState"), "to": x.get("newState"), "labels": {}})
    return out


def defined_rules(root=None):
    """저장소 정의의 (UID, 제목) — apply.sh 가 올리는 것."""
    root = root or MEASURE_DIR.parent.parent
    out = []
    for p in sorted(root.glob(RULES_GLOB)):
        d = read_json(p, {}) or {}
        for r in d.get("rules") or []:
            out.append({"uid": r.get("uid"), "title": r.get("title"), "group": d.get("title")})
    return out


def live_rules(r):
    if not isinstance(r, list):
        return None
    return [{"uid": x.get("uid"), "title": x.get("title"), "group": x.get("ruleGroup")} for x in r if isinstance(x, dict)]


def _is(state, names):
    return bool(state) and str(state).split(" ", 1)[0] in names


def analyze(rdir: Path, win=None, root=None):
    raw = read_json(rdir / "alerts.json")
    if not raw:
        return None
    # 받은 응답이 하나라도 있으면 「측정함」이다 — 전환이 0건이면 「울리지 않았다」가 답이다
    trans = from_history(raw.get("history"))
    source = "rules/history" if raw.get("history") is not None else None
    if not trans:
        ann = from_annotations(raw.get("annotations"))
        if ann or (source is None and raw.get("annotations") is not None):
            trans, source = ann, "annotations"
    trans = sorted((t for t in trans if t["time_ms"] is not None), key=lambda t: t["time_ms"])
    # 같은 전환이 두 번 오면(여러 프레임 · 인스턴스) 하나로
    seen, uniq = set(), []
    for t in trans:
        k = (t["time_ms"], t["rule_uid"] or t["rule_title"], t["from"], t["to"], json.dumps(t["labels"], sort_keys=True))
        if k not in seen:
            seen.add(k)
            uniq.append(t)
    trans = uniq
    start_ms = raw.get("round_start_ms") or ((win.start * 1000) if win is not None and win.start is not None else None)
    end_ms = raw.get("round_end_ms") or ((win.end * 1000) if win is not None and win.end is not None else None)
    for t in trans:
        t["minutes_after_start"] = rnd((t["time_ms"] - start_ms) / 60000.0, 1) if start_ms else None
        t["after_end"] = bool(end_ms and t["time_ms"] > end_ms)
    fired = {}
    for t in trans:
        if _is(t["to"], FIRING):
            key = t["rule_title"] or t["rule_uid"] or "?"
            fired.setdefault(key, t)
    states = defaultdict(int)
    for t in trans:
        states[str(t["to"])] += 1
    defined = defined_rules(root)
    live = live_rules(raw.get("rules"))
    cmp_ = None
    if live is not None:
        dt, lt = {d["title"] for d in defined}, {x["title"] for x in live}
        cmp_ = {"missing_live": sorted(dt - lt), "extra_live": sorted(lt - dt), "same": dt == lt}
    errors = {k: raw.get(k) for k in ("history_error", "annotations_error", "rules_error") if raw.get(k)}
    return {
        "source": source,
        "available": source is not None,
        "from_ms": raw.get("from_ms"), "to_ms": raw.get("to_ms"), "captured_utc": raw.get("captured_utc"),
        "transitions": trans,
        "states": dict(states),
        "fired": [{"rule": k, "time_ms": t["time_ms"], "minutes_after_start": t["minutes_after_start"], "after_end": t["after_end"]}
                  for k, t in sorted(fired.items(), key=lambda kv: kv[1]["time_ms"])],
        "rules_defined": defined,
        "rules_live": live,
        "rules_compare": cmp_,
        "errors": errors or None,
    }
