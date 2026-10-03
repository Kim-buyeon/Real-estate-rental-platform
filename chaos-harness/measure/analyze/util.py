"""공용 도구 — 백분위 · 시각 · 경로 정규화 · 파일 열기."""
from __future__ import annotations

import gzip
import json
import math
import re
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.parse import urlsplit

KST = timezone(timedelta(hours=9))

# 측정 폴더 기준 경로
MEASURE_DIR = Path(__file__).resolve().parent.parent
RESULTS_DIR = MEASURE_DIR / "results"
REPORT_SRC_DIR = MEASURE_DIR / "report"


def percentile(values, p):
    """선형 보간 백분위(numpy 기본 · Excel PERCENTILE.INC 와 같다). 비면 None."""
    if not values:
        return None
    s = sorted(values)
    if len(s) == 1:
        return float(s[0])
    k = (len(s) - 1) * (p / 100.0)
    lo = math.floor(k)
    hi = math.ceil(k)
    if lo == hi:
        return float(s[int(k)])
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def mean(values):
    return sum(values) / len(values) if values else None


def median(values):
    return percentile(values, 50)


def rnd(x, n=2):
    return None if x is None else round(float(x), n)


def parse_time(v):
    """ISO 문자열 · 초 · 밀리초 epoch 를 UTC epoch 초(float)로."""
    if v is None or v == "":
        return None
    if isinstance(v, (int, float)):
        f = float(v)
        return f / 1000.0 if f > 1e11 else f
    s = str(v).strip()
    try:
        f = float(s)
        return f / 1000.0 if f > 1e11 else f
    except ValueError:
        pass
    s = s.replace("Z", "+00:00")
    dt = datetime.fromisoformat(s)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.timestamp()


def fmt_kst(epoch, with_date=True):
    if epoch is None:
        return None
    dt = datetime.fromtimestamp(epoch, KST)
    return dt.strftime("%Y-%m-%d %H:%M" if with_date else "%H:%M:%S")


def open_text(path: Path):
    """.gz 면 풀어서 연다."""
    if str(path).endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    return open(path, "r", encoding="utf-8", errors="replace")


def read_json(path: Path, default=None):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def write_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


_NUM_SEG = re.compile(r"^(\d+|[0-9a-fA-F-]{16,})$")
_VAR_SEG = re.compile(r"^\{[^}]*\}$")


def route_key(path: str | None) -> str | None:
    """경로를 엔드포인트 키로 — 질의 문자열 제거, 숫자 · 변수 조각은 {id}, 앞 /api/ 제거.
    Nginx 경로 · JMeter URL · 추적 http.route 가 같은 키로 모인다."""
    if not path:
        return None
    p = urlsplit(path).path if "://" in path else path.split("?", 1)[0]
    segs = []
    for seg in p.split("/"):
        if not seg:
            continue
        if _NUM_SEG.match(seg) or _VAR_SEG.match(seg):
            segs.append("{id}")
        else:
            segs.append(seg)
    if segs and segs[0] == "api":
        segs = segs[1:]
    return "/".join(segs) if segs else "/"


def canon_node(name: str) -> str:
    """파일 이름의 노드(app01 · db02)를 보고서 표기(app-01 · db-02)로."""
    m = re.match(r"^([a-zA-Z]+)-?(\d+)$", name or "")
    return f"{m.group(1).lower()}-{m.group(2)}" if m else name


def file_node(canon: str) -> str:
    """보고서 표기 → 파일 이름 표기(app-01 → app01)."""
    return (canon or "").replace("-", "")


class Window:
    """회차 구간. 워밍업을 뺀 [lo, hi] 가 통계 구간이다."""

    def __init__(self, start, end, warmup=0):
        self.start = start
        self.end = end
        self.warmup = warmup or 0

    @property
    def lo(self):
        return None if self.start is None else self.start + self.warmup

    @property
    def hi(self):
        return self.end

    @property
    def duration(self):
        if self.lo is None or self.hi is None:
            return None
        return max(self.hi - self.lo, 1e-9)

    def contains(self, t):
        if t is None:
            return False
        if self.lo is not None and t < self.lo:
            return False
        if self.hi is not None and t > self.hi:
            return False
        return True

    def in_round(self, t):
        """워밍업 포함 회차 전체 구간."""
        if self.start is not None and t < self.start:
            return False
        if self.end is not None and t > self.end:
            return False
        return True
