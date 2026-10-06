"""노드 지표(Prometheus 텍스트 노출 형식) — 파서와 자원 집계. [7.4] [5.1] [4.6] · G2.

입력: metrics/<node>.prom.gz — `# SCRAPE <epoch> <target>` 표시 줄 뒤에 그 긁기의 본문이 이어진다.
"""
from __future__ import annotations

import re
import zlib
from collections import defaultdict
from pathlib import Path

from .util import open_text, rnd, percentile, canon_node, route_key

# 필요한 이름만 남긴다 — 노드 하나가 시간당 수백만 표본이라 다 들고 있으면 메모리가 모자란다
KEEP_PREFIXES = (
    "node_cpu_seconds_total", "node_load1", "node_memory_MemAvailable_bytes", "node_memory_MemTotal_bytes",
    "node_disk_io_time_seconds_total", "node_disk_reads_completed_total", "node_disk_writes_completed_total",
    "node_disk_read_bytes_total", "node_disk_written_bytes_total", "node_netstat_TcpExt_ListenOverflows",
    "node_sockstat_TCP_tw", "node_nf_conntrack_entries", "node_vmstat_oom_kill",
    "node_network_receive_bytes_total", "node_network_transmit_bytes_total",
    "tomcat_threads_", "hikaricp_connections", "jvm_memory_used_bytes", "jvm_memory_max_bytes",
    "jvm_gc_pause_seconds", "jvm_gc_memory_allocated_bytes_total", "jvm_threads_live_threads",
    "http_server_requests_seconds_count", "http_server_requests_seconds_sum", "lettuce_",
    "executor_queued_tasks", "executor_active_threads", "process_files_open_files", "process_cpu_usage",
    "process_start_time_seconds", "container_memory",
    "redis_keyspace_", "redis_evicted_keys_total", "redis_memory_used_bytes", "redis_memory_max_bytes",
    "redis_connected_clients", "nginx_", "pg_", "rental_pg_",
    # 관측 설계서 3.2 의 나머지(#390) — 이름은 node exporter v1.9.1 기본 수집기(stat · vmstat · filesystem · diskstats ·
    # netdev · netstat · timex)와 운영 Compose 가 켠 systemd 수집기의 것. node_filesystem_files 는 files_free 까지 걸린다
    "node_procs_running", "node_procs_blocked", "node_vmstat_pgmajfault",
    "node_filesystem_avail_bytes", "node_filesystem_size_bytes", "node_filesystem_files", "node_filesystem_readonly",
    "node_disk_read_time_seconds_total", "node_disk_write_time_seconds_total",
    "node_network_receive_errs_total", "node_network_transmit_errs_total",
    "node_network_receive_drop_total", "node_network_transmit_drop_total",
    "node_netstat_Tcp_RetransSegs", "node_netstat_Tcp_OutSegs", "node_netstat_Tcp_CurrEstab",
    "node_timex_sync_status", "node_timex_offset_seconds", "node_boot_time_seconds", "node_systemd_unit_state",
)
# W1 서버 쪽 지연 — application.yml 의 slo(100ms · 300ms · 500ms · 1s · 3s)가 낸 누적 버킷. 대조용(양식 [0.2])
KEEP_BUCKETS = ("hikaricp_connections_acquire_seconds_bucket", "http_server_requests_seconds_bucket")

APP_VCPU = 2
DB_VCPU = 2
# 인스턴스 쪽 EBS 기준선(IOPS · 바이트/초 · 유형) — aws ec2 describe-instance-types(ap-northeast-2, 2026-10-05 조회)의
# BaselineIops · BaselineThroughputInMBps. 볼륨(gp3 3,000 IOPS · 125 MB/s)보다 낮아 먼저 닿는다
EBS_BASELINE = {"t3.small": (1000.0, 21.75e6), "t3.medium": (2000.0, 43.375e6)}
APP_TYPE_NOW = "t3.medium"          # 현재 앱 노드 구성(#394) — 회차에 MemTotal 이 없을 때 쓴다
DB_TYPE_NOW = "t3.small"            # 현재 DB 노드 구성(#447 에서 올렸다가 #454 에서 되돌림) — 회차에 MemTotal 이 없을 때 쓴다
# 노드 유형은 회차의 실측 MemTotal 로 고른다(앱 노드 #394 전 회차는 t3.small, DB 노드는 #447 ~ #454 사이만 t3.medium). 문턱은 AWS 사양 MemoryInfo
# 2,048 · 4,096 MiB 의 가운데 — 새 유형이 생기면 표에 더한다
TYPE_BY_MEM = ((3072 * 2**20, "t3.small"), (None, "t3.medium"))


def ebs_type(is_db, mem_total_bytes):
    """노드의 인스턴스 유형과 추정 여부 — (유형, 추정?). MemTotal 이 없으면 지금 구성으로 추정."""
    if not mem_total_bytes:
        return (DB_TYPE_NOW if is_db else APP_TYPE_NOW), True
    for upper, t in TYPE_BY_MEM:
        if upper is None or mem_total_bytes < upper:
            return t, False
PG_MAX_CONN_DEFAULT = 48
CONNTRACK_DEFAULT = 65536
_DISK_DEV = re.compile(r"^(nvme\d+n\d+|xvd[a-z]+|sd[a-z]+|vd[a-z]+)$")
_NET_DEV = re.compile(r"^(eth|ens|enp|eno)")
# 실제 마운트만 — 메모리 · 가상 파일시스템과 컨테이너 자리는 뺀다(O6)
_FS_SKIP_TYPES = {"tmpfs", "devtmpfs", "overlay", "squashfs", "nsfs", "proc", "sysfs", "cgroup", "cgroup2", "autofs",
                  "rpc_pipefs", "ramfs", "fuse.lxcfs", "devpts", "mqueue", "hugetlbfs", "erofs"}
_FS_SKIP_MOUNTS = ("/run", "/var/lib/docker", "/var/lib/containers", "/sys", "/proc", "/dev")


# ---------------------------------------------------------------- 파서

def parse_sample(line: str):
    """`name{a="b",c="d"} value [ts]` → (name, labels dict, value). 형식이 아니면 None."""
    line = line.strip()
    if not line or line[0] == "#":
        return None
    i = 0
    n = len(line)
    while i < n and line[i] not in "{ \t":
        i += 1
    name = line[:i]
    labels = {}
    if i < n and line[i] == "{":
        i += 1
        while i < n and line[i] != "}":
            while i < n and line[i] in ", \t":
                i += 1
            if i < n and line[i] == "}":
                break
            j = line.index("=", i)
            key = line[i:j].strip()
            i = j + 1
            if line[i] != '"':
                return None
            i += 1
            buf = []
            while i < n and line[i] != '"':
                if line[i] == "\\" and i + 1 < n:
                    nxt = line[i + 1]
                    buf.append({"n": "\n", "\\": "\\", '"': '"'}.get(nxt, nxt))
                    i += 2
                    continue
                buf.append(line[i])
                i += 1
            labels[key] = "".join(buf)
            i += 1
        i += 1
    rest = line[i:].split()
    if not rest:
        return None
    try:
        v = float(rest[0])
    except ValueError:
        return None
    return name, labels, v


def _keep(name):
    if name.endswith("_bucket"):
        return name.startswith(KEEP_BUCKETS)
    return name.startswith(KEEP_PREFIXES)


def _chunks(path: Path):
    """(덩어리 번호, 글). gzip 이면 멤버마다 번호 0, 1, …, 아니면 파일 전체 하나(번호 −1 — 멤버 경계 없음).
    멤버 수천 개 · 수백 MB 를 위해 64 KiB 씩 흘려 넣는다(남은 바이트를 매번 잘라 복사하지 않는다)."""
    if not str(path).endswith(".gz"):
        with open_text(path) as f:
            yield -1, f.read()
        return
    no = 0
    d = zlib.decompressobj(16 + zlib.MAX_WBITS)
    buf = []
    with open(path, "rb") as f:
        pending = b""
        while True:
            block = pending or f.read(65536)
            pending = b""
            if not block:
                break
            try:
                buf.append(d.decompress(block))
            except zlib.error:
                return                  # 깨진 꼬리 — 여기까지만
            if d.eof:
                yield no, b"".join(buf).decode("utf-8", errors="replace")
                no += 1
                buf = []
                pending = d.unused_data
                d = zlib.decompressobj(16 + zlib.MAX_WBITS)
    if buf and any(buf):
        # 반쯤 쓰인 마지막 멤버 — 받은 만큼만(본문은 다음 표시 줄이 없어 그대로 확정되지 않게 버린다)
        return


class NodeMetrics:
    """노드 하나의 긁기 기록. data[target][name][labels] = [(ts, v), ...]."""

    def __init__(self, node):
        self.node = node
        self.data = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
        self.scrapes = defaultdict(list)

    @classmethod
    def load(cls, path: Path, node: str, keep_all=False):
        """긁기 본문은 다음 표시 줄(또는 파일 끝)에서 확정한다. 실패 표시 두 모양을 다 받는다.
        ① 지금 수집기 — 본문 없는 `# SCRAPE_ERROR <대상>` 한 줄(멤버 하나): 앞 본문은 온전하니 그대로 둔다
        ② 예전 수집기 — 머리 · 잘린 본문 · `# SCRAPE_ERROR` 가 한 gzip 멤버: 그 본문을 통째로 버린다
        gzip 이 아니면 멤버 경계가 없어 「같은 대상의 본문 바로 뒤」면 ② 로 본다."""
        m = cls(node)
        cur = None   # [ts, target, rows, 시작한 덩어리 번호]

        def commit():
            if cur is None or cur[0] is None:
                return
            ts, target, rows, _ = cur
            m.scrapes[target].append(ts)
            for name, labels, v in rows:
                m.data[target][name][tuple(sorted(labels.items()))].append((ts, v))

        for chunk_no, text in _chunks(path):
            for line in text.splitlines():
                if line.startswith("# SCRAPE_ERROR"):
                    parts = line.split()
                    target = parts[2] if len(parts) > 2 else None
                    same_member = chunk_no >= 0 and cur is not None and cur[3] == chunk_no
                    plain_same = chunk_no < 0 and cur is not None and cur[1] == target
                    if same_member or plain_same:
                        cur = None          # 잘린 본문 — 버린다
                    else:
                        commit()            # 앞 본문은 온전하다
                        cur = None
                    continue
                if line.startswith("# SCRAPE "):
                    commit()
                    parts = line.split()
                    try:
                        ts = float(parts[2])
                    except (IndexError, ValueError):
                        ts = None
                    cur = [ts, parts[3] if len(parts) > 3 else "unknown", [], chunk_no]
                    continue
                if cur is None or line.startswith("#"):
                    continue
                smp = parse_sample(line)
                if not smp or (not keep_all and not _keep(smp[0])):
                    continue
                cur[2].append(smp)
        commit()
        return m

    def targets(self):
        return list(self.data.keys())

    def has(self, target, name):
        return name in self.data.get(target, {})

    def agg(self, target, name, filt=None, by=None):
        """라벨 조건으로 거른 뒤 시각별 합. by 가 있으면 그 라벨로 묶는다 → {group: {ts: v}}."""
        out = defaultdict(lambda: defaultdict(float))
        for lab, pts in self.data.get(target, {}).get(name, {}).items():
            d = dict(lab)
            if filt and not filt(d):
                continue
            g = d.get(by) if by else None
            for t, v in pts:
                out[g][t] += v
        return out


# ---------------------------------------------------------------- 시계열 도구

def _in(pts, win, whole=False):
    lo = win.start if whole else win.lo
    return [(t, v) for t, v in sorted(pts) if (lo is None or t >= lo) and (win.hi is None or t <= win.hi)]


def increase(pts):
    """카운터 증가량 — 재시작(값 감소)이면 새 값부터 더한다."""
    inc = 0.0
    for (t0, v0), (t1, v1) in zip(pts, pts[1:]):
        inc += v1 - v0 if v1 >= v0 else v1
    return inc


def win_increase(pts, win):
    """구간 안 표본들의 증가량을 통계 구간 길이로 늘린다(Prometheus increase() 의 외삽과 같다).
    5초 긁기라 구간 양 끝의 몫이 빠지는 것을 메운다 — 증가량 ÷ 구간 길이가 그 구간의 초당 값이 된다."""
    inc = increase(pts)
    if len(pts) < 2 or not win.duration:
        return inc
    span = pts[-1][0] - pts[0][0]
    return inc * (win.duration / span) if span > 0 else inc


def rates(pts):
    """연속 두 표본의 초당 변화량 [(t, rate)]."""
    out = []
    for (t0, v0), (t1, v1) in zip(pts, pts[1:]):
        if t1 <= t0:
            continue
        d = v1 - v0 if v1 >= v0 else v1
        out.append((t1, d / (t1 - t0)))
    return out


def gstats(pts):
    """게이지 — 최대 · 최소 · 평균 · 끝값 · 처음값."""
    if not pts:
        return None
    vs = [v for _, v in pts]
    return {"max": rnd(max(vs), 3), "min": rnd(min(vs), 3), "mean": rnd(sum(vs) / len(vs), 3),
            "end": rnd(vs[-1], 3), "start": rnd(vs[0], 3)}


def _series(d):
    """{ts: v} → 정렬된 [(t, v)]."""
    return sorted(d.items())


# ---------------------------------------------------------------- 노드(OS)

def node_os(m: NodeMetrics, win, missing):
    t = "node"
    res = {}
    # CPU — 모드별 합을 시각별로, 연속 두 표본으로 비율
    cpu = m.agg(t, "node_cpu_seconds_total", by="mode")
    if cpu:
        stamps = sorted(set().union(*[set(d.keys()) for d in cpu.values()]))
        stamps = [s for s in stamps if (win.start is None or s >= win.start) and (win.hi is None or s <= win.hi)]
        series = []
        modes_acc = defaultdict(list)
        for a, b in zip(stamps, stamps[1:]):
            deltas = {}
            for mode, d in cpu.items():
                if a in d and b in d:
                    deltas[mode] = max(d[b] - d[a], 0.0)
            tot = sum(deltas.values())
            if tot <= 0:
                continue
            busy = 100.0 * (1 - deltas.get("idle", 0.0) / tot)
            series.append((b, busy))
            if win.contains(b):
                for mode in ("user", "system", "iowait", "steal"):
                    modes_acc[mode].append(100.0 * deltas.get(mode, 0.0) / tot)
        inwin = [v for s, v in series if win.contains(s)]
        res["cpu_pct"] = {"max": rnd(max(inwin), 1) if inwin else None,
                          "mean": rnd(sum(inwin) / len(inwin), 1) if inwin else None,
                          "p95": rnd(percentile(inwin, 95), 1),
                          "end": rnd(inwin[-1], 1) if inwin else None}
        res["cpu_modes_pct"] = {k: {"mean": rnd(sum(v) / len(v), 2), "max": rnd(max(v), 2)} for k, v in modes_acc.items() if v}
        res["_cpu_ts"] = series
        vcpu = len({dict(l).get("cpu") for l in m.data[t]["node_cpu_seconds_total"].keys()})
        res["vcpu"] = vcpu
    else:
        missing.append(f"{m.node}:node_cpu_seconds_total")
        res["cpu_pct"] = None
        vcpu = None
    load = _series(m.agg(t, "node_load1").get(None, {}))
    if load and vcpu:
        lp = [(s, v / vcpu) for s, v in _in(load, win)]
        res["load_per_vcpu"] = gstats(lp)
    else:
        res["load_per_vcpu"] = None
        if not load:
            missing.append(f"{m.node}:node_load1")
    avail = _in(_series(m.agg(t, "node_memory_MemAvailable_bytes").get(None, {})), win)
    total = _series(m.agg(t, "node_memory_MemTotal_bytes").get(None, {}))
    res["mem_available_min_bytes"] = min(v for _, v in avail) if avail else None
    res["mem_total_bytes"] = total[-1][1] if total else None
    if avail and total:
        tot = total[-1][1]
        res["_mem_ts"] = [(s, 100.0 * (1 - v / tot)) for s, v in _in(_series(m.agg(t, "node_memory_MemAvailable_bytes").get(None, {})), win, whole=True)]
        res["mem_used_pct_max"] = rnd(100.0 * (1 - res["mem_available_min_bytes"] / tot), 1)
    else:
        missing.append(f"{m.node}:node_memory_MemAvailable_bytes")
    # 디스크 — 장치별 util% 최대, IOPS · 처리량은 장치 합
    devf = lambda d: bool(_DISK_DEV.match(d.get("device", "")))
    util = m.agg(t, "node_disk_io_time_seconds_total", filt=devf, by="device")
    if util:
        umax = 0.0
        uts = defaultdict(float)
        for dev, d in util.items():
            for s, r in rates(_in(_series(d), win, whole=True)):
                uts[s] = max(uts[s], 100.0 * r)
                if win.contains(s):
                    umax = max(umax, 100.0 * r)
        res["disk_util_pct_max"] = rnd(umax, 1)
        res["_disk_ts"] = sorted(uts.items())

        def sum_rate(name):
            d = m.agg(t, name, filt=devf).get(None, {})
            rs = [r for s, r in rates(_in(_series(d), win))]
            return rs

        iops = [a + b for a, b in zip(sum_rate("node_disk_reads_completed_total"), sum_rate("node_disk_writes_completed_total"))]
        thr = [a + b for a, b in zip(sum_rate("node_disk_read_bytes_total"), sum_rate("node_disk_written_bytes_total"))]
        res["disk_iops"] = {"max": rnd(max(iops), 1) if iops else None, "mean": rnd(sum(iops) / len(iops), 1) if iops else None}
        res["disk_bps"] = {"max": rnd(max(thr), 0) if thr else None, "mean": rnd(sum(thr) / len(thr), 0) if thr else None}
    else:
        missing.append(f"{m.node}:node_disk_io_time_seconds_total")
        res["disk_util_pct_max"] = None
        res["disk_iops"] = None
        res["disk_bps"] = None
    # 네트워크 — 물리 장치만
    netf = lambda d: bool(_NET_DEV.match(d.get("device", "")))
    rx = [r for _, r in rates(_in(_series(m.agg(t, "node_network_receive_bytes_total", filt=netf).get(None, {})), win))]
    tx = [r for _, r in rates(_in(_series(m.agg(t, "node_network_transmit_bytes_total", filt=netf).get(None, {})), win))]
    res["net_bps_max"] = {"rx": rnd(max(rx), 0) if rx else None, "tx": rnd(max(tx), 0) if tx else None}
    # TCP · conntrack · OOM
    lo = _in(_series(m.agg(t, "node_netstat_TcpExt_ListenOverflows").get(None, {})), win)
    res["listen_overflows_delta"] = rnd(increase(lo), 0) if lo else None
    tw = _in(_series(m.agg(t, "node_sockstat_TCP_tw").get(None, {})), win)
    res["time_wait_max"] = max(v for _, v in tw) if tw else None
    ce = _in(_series(m.agg(t, "node_nf_conntrack_entries").get(None, {})), win)
    cl = _series(m.agg(t, "node_nf_conntrack_entries_limit").get(None, {}))
    if ce:
        lim = cl[-1][1] if cl else None
        cmax = max(v for _, v in ce)
        res["conntrack"] = {"max": cmax, "limit": lim, "ratio_max_pct": rnd(100.0 * cmax / lim, 2) if lim else None}
    else:
        res["conntrack"] = None
    oom = _in(_series(m.agg(t, "node_vmstat_oom_kill").get(None, {})), win)
    res["oom_kills_delta"] = rnd(increase(oom), 0) if oom else None
    for k, v in (("listen_overflows_delta", "node_netstat_TcpExt_ListenOverflows"), ("time_wait_max", "node_sockstat_TCP_tw"),
                 ("oom_kills_delta", "node_vmstat_oom_kill")):
        if res.get(k) is None:
            missing.append(f"{m.node}:{v}")
    node_os_extra(m, win, missing, res, devf, netf)
    return res


def node_os_extra(m: NodeMetrics, win, missing, res, devf, netf):
    """관측 설계서 3.2 의 O2 · O4 · O6 · O8 · O9 · O10 · O12 · O13 · O14 (#390). 없으면 missing 에 이름을 남긴다."""
    t = "node"

    def g(name, filt=None, whole=False):
        return _in(_series(m.agg(t, name, filt=filt).get(None, {})), win, whole=whole)

    def note(key, name):
        if res.get(key) is None:
            missing.append(f"{m.node}:{name}")

    # O2 — 실행 대기 · IO 에 막힌 프로세스 수(게이지, stat 수집기)
    res["procs_running"] = gstats(g("node_procs_running"))
    res["procs_blocked"] = gstats(g("node_procs_blocked"))
    note("procs_running", "node_procs_running")
    # O4 — 주요 페이지 폴트(디스크에서 다시 읽은 페이지) 초당
    pf = g("node_vmstat_pgmajfault")
    if pf:
        rs = [r for _, r in rates(pf)]
        res["pgmajfault_per_sec"] = {"max": rnd(max(rs), 2) if rs else None, "mean": rnd(sum(rs) / len(rs), 2) if rs else None,
                                     "delta": rnd(win_increase(pf, win), 0)}
    else:
        res["pgmajfault_per_sec"] = None
    note("pgmajfault_per_sec", "node_vmstat_pgmajfault")
    # O6 — 실제 마운트별 사용률 · inode · 읽기 전용
    fsf = lambda d: d.get("fstype") not in _FS_SKIP_TYPES and not d.get("mountpoint", "").startswith(_FS_SKIP_MOUNTS)
    fss = {}
    for mp, d in m.agg(t, "node_filesystem_avail_bytes", filt=fsf, by="mountpoint").items():
        mf = (lambda x: (lambda d_: fsf(d_) and d_.get("mountpoint") == x))(mp)
        av = _in(_series(d), win, whole=True)
        size = _series(m.agg(t, "node_filesystem_size_bytes", filt=mf).get(None, {}))
        files = _series(m.agg(t, "node_filesystem_files", filt=mf).get(None, {}))
        ffree = _in(_series(m.agg(t, "node_filesystem_files_free", filt=mf).get(None, {})), win, whole=True)
        ro = _in(_series(m.agg(t, "node_filesystem_readonly", filt=mf).get(None, {})), win, whole=True)
        fstype = next((dict(l).get("fstype") for l in m.data[t]["node_filesystem_avail_bytes"] if dict(l).get("mountpoint") == mp), None)
        sz = size[-1][1] if size else None
        amin = min(v for _, v in av) if av else None
        fl = files[-1][1] if files else None
        fmin = min(v for _, v in ffree) if ffree else None
        fss[mp] = {"fstype": fstype, "size_bytes": sz, "avail_min_bytes": amin,
                   "used_pct_max": rnd(100.0 * (1 - amin / sz), 1) if sz and amin is not None else None,
                   "inodes_used_pct_max": rnd(100.0 * (1 - fmin / fl), 1) if fl and fmin is not None else None,
                   "readonly": (max(v for _, v in ro) >= 1) if ro else None}
    res["filesystems"] = fss or None
    res["fs_used_pct_max"] = max((v["used_pct_max"] for v in fss.values() if v["used_pct_max"] is not None), default=None)
    res["fs_inodes_used_pct_max"] = max((v["inodes_used_pct_max"] for v in fss.values() if v["inodes_used_pct_max"] is not None), default=None)
    res["fs_readonly"] = any(v["readonly"] for v in fss.values()) if fss else None
    note("filesystems", "node_filesystem_avail_bytes")
    # O8 — 요청당 대기 시간 = (읽기 + 쓰기 시간 증가) ÷ (완료 수 증가). iostat 의 r_await · w_await 와 같은 정의
    rt, wt = g("node_disk_read_time_seconds_total", devf), g("node_disk_write_time_seconds_total", devf)
    rc, wc = g("node_disk_reads_completed_total", devf), g("node_disk_writes_completed_total", devf)
    if rt and wt and rc and wc:
        drt, dwt, drc, dwc = increase(rt), increase(wt), increase(rc), increase(wc)
        # 긁기 사이마다의 값 — 그중 최대(짧은 몰림)
        tm = defaultdict(float)
        cn = defaultdict(float)
        for pts, acc in ((rt, tm), (wt, tm), (rc, cn), (wc, cn)):
            for s_, v in pts:
                acc[s_] += v
        stamps = sorted(set(tm) & set(cn))
        per = []
        for a, b in zip(stamps, stamps[1:]):
            dn, dt_ = cn[b] - cn[a], tm[b] - tm[a]
            if dn > 0 and dt_ >= 0:
                per.append(1000.0 * dt_ / dn)
        res["disk_await_ms"] = {"read": rnd(1000.0 * drt / drc, 3) if drc else None,
                                "write": rnd(1000.0 * dwt / dwc, 3) if dwc else None,
                                "all": rnd(1000.0 * (drt + dwt) / (drc + dwc), 3) if drc + dwc else None,
                                "max_interval": rnd(max(per), 3) if per else None}
    else:
        res["disk_await_ms"] = None
    note("disk_await_ms", "node_disk_read_time_seconds_total")
    # O9 — 물리 장치의 오류 · 버린 패킷(회차 증가) · TCP 재전송
    net = {}
    for k, n in (("rx_errs", "node_network_receive_errs_total"), ("tx_errs", "node_network_transmit_errs_total"),
                 ("rx_drop", "node_network_receive_drop_total"), ("tx_drop", "node_network_transmit_drop_total")):
        pts = g(n, netf)
        net[k] = rnd(increase(pts), 0) if pts else None
    res["net_errors"] = net if any(v is not None for v in net.values()) else None
    note("net_errors", "node_network_receive_errs_total")
    rs_, os_ = g("node_netstat_Tcp_RetransSegs"), g("node_netstat_Tcp_OutSegs")
    if rs_:
        d_rs = increase(rs_)
        d_os = increase(os_) if os_ else None
        res["tcp_retrans"] = {"delta": rnd(d_rs, 0), "per_sec": rnd(d_rs / (win.duration or 1), 3),
                              "ratio_pct": rnd(100.0 * d_rs / d_os, 3) if d_os else None}
    else:
        res["tcp_retrans"] = None
    note("tcp_retrans", "node_netstat_Tcp_RetransSegs")
    # O10 — 맺어진 TCP 연결 수
    res["tcp_estab"] = gstats(g("node_netstat_Tcp_CurrEstab"))
    note("tcp_estab", "node_netstat_Tcp_CurrEstab")
    # O12 — 시간 동기(1 = 동기) · 오차. 워밍업 포함 회차 전체
    ss, off = g("node_timex_sync_status", whole=True), g("node_timex_offset_seconds", whole=True)
    res["time_sync"] = ({"synced_min": min(v for _, v in ss),
                         "offset_abs_max_ms": rnd(max(abs(v) for _, v in off) * 1000, 3) if off else None} if ss else None)
    note("time_sync", "node_timex_sync_status")
    # O13 — 부팅 시각이 회차 중에 바뀌면 재부팅
    bt = g("node_boot_time_seconds", whole=True)
    res["boot"] = {"rebooted": len({round(v) for _, v in bt}) > 1, "boot_time": bt[-1][1]} if bt else None
    note("boot", "node_boot_time_seconds")
    # O14 — 회차 구간에 한 번이라도 failed 였던 유닛. 노드에서 직접 긁으므로 상태 다섯 줄이 다 온다 — failed 만 본다
    su = m.data.get(t, {}).get("node_systemd_unit_state", {})
    if su:
        failed = set()
        for lab, pts in su.items():
            d = dict(lab)
            if d.get("state") == "failed" and any(v >= 1 for _, v in _in(sorted(pts), win, whole=True)):
                failed.add(d.get("name", "?"))
        res["systemd_failed"] = sorted(failed)
    else:
        res["systemd_failed"] = None
    note("systemd_failed", "node_systemd_unit_state")


# ---------------------------------------------------------------- 앱 슬롯

def _hist_quantile(buckets: dict, q):
    """누적 버킷 {le: count} 에서 분위 (Prometheus histogram_quantile 과 같은 선형 보간)."""
    items = sorted(((float("inf") if k in ("+Inf", "inf") else float(k)), v) for k, v in buckets.items())
    if not items or items[-1][1] <= 0:
        return None
    total = items[-1][1]
    rank = q * total
    prev_le, prev_c = 0.0, 0.0
    for le, c in items:
        if c >= rank:
            if le == float("inf"):
                return prev_le
            return prev_le + (le - prev_le) * ((rank - prev_c) / (c - prev_c) if c > prev_c else 0)
        prev_le, prev_c = le, c
    return None


def app_slot(m: NodeMetrics, target, win, missing):
    res = {}
    slot = f"{m.node}/{target}"

    def gauge(name, filt=None):
        return _in(_series(m.agg(target, name, filt=filt).get(None, {})), win)

    def delta(name, filt=None):
        pts = gauge(name, filt)
        return win_increase(pts, win) if pts else None

    busy = gauge("tomcat_threads_busy_threads")
    tmax = gauge("tomcat_threads_config_max_threads")
    res["tomcat_busy"] = gstats(busy)
    res["tomcat_max"] = tmax[-1][1] if tmax else None
    res["tomcat_queue_suspected"] = (bool(busy and tmax and max(v for _, v in busy) >= tmax[-1][1]) if busy and tmax else None)
    res["_tomcat_ts"] = [(s, v) for s, v in _in(_series(m.agg(target, "tomcat_threads_busy_threads").get(None, {})), win, whole=True)]
    if not busy:
        missing.append(f"{slot}:tomcat_threads_busy_threads")
    # 커넥션 풀 — pool 라벨별
    pools = {}
    for pool in {dict(l).get("pool") for n in ("hikaricp_connections_pending", "hikaricp_connections_max")
                 for l in m.data.get(target, {}).get(n, {}).keys()}:
        f = (lambda p: (lambda d: d.get("pool") == p))(pool)
        pend = gauge("hikaricp_connections_pending", f)
        act = gauge("hikaricp_connections_active", f)
        pmx = gauge("hikaricp_connections_max", f)
        a_sum = delta("hikaricp_connections_acquire_seconds_sum", f)
        a_cnt = delta("hikaricp_connections_acquire_seconds_count", f)
        a_max = gauge("hikaricp_connections_acquire_seconds_max", f)
        u_sum = delta("hikaricp_connections_usage_seconds_sum", f)
        u_cnt = delta("hikaricp_connections_usage_seconds_count", f)
        u_max = gauge("hikaricp_connections_usage_seconds_max", f)
        tmo = delta("hikaricp_connections_timeout_total", f)
        # 히스토그램이 있으면 구간 증가량으로 p95
        a_p95 = None
        bk = m.agg(target, "hikaricp_connections_acquire_seconds_bucket", filt=f, by="le")
        if bk:
            inc = {le: increase(_in(_series(d), win)) for le, d in bk.items()}
            q = _hist_quantile(inc, 0.95)
            a_p95 = rnd(q * 1000, 3) if q is not None else None
        pools[pool] = {
            "pending_max": max((v for _, v in pend), default=None),
            "active_max": max((v for _, v in act), default=None),
            "max": pmx[-1][1] if pmx else None,
            "acquire_mean_ms": rnd(a_sum / a_cnt * 1000, 3) if a_sum is not None and a_cnt else None,
            "acquire_max_ms": rnd(max(v for _, v in a_max) * 1000, 3) if a_max else None,
            "acquire_p95_ms": a_p95,
            "usage_mean_ms": rnd(u_sum / u_cnt * 1000, 3) if u_sum is not None and u_cnt else None,
            "usage_max_ms": rnd(max(v for _, v in u_max) * 1000, 3) if u_max else None,
            "timeouts": rnd(tmo, 0) if tmo is not None else None,
            "_active_ts": _in(_series(m.agg(target, "hikaricp_connections_active", filt=f).get(None, {})), win, whole=True),
            "_pending_ts": _in(_series(m.agg(target, "hikaricp_connections_pending", filt=f).get(None, {})), win, whole=True),
        }
    res["hikari"] = pools
    if not pools:
        missing.append(f"{slot}:hikaricp_connections_pending")
    # 메모리
    heapf = lambda d: d.get("area") == "heap"
    nonf = lambda d: d.get("area") == "nonheap"
    heap = gauge("jvm_memory_used_bytes", heapf)
    res["heap_used"] = gstats(heap)
    res["nonheap_used"] = gstats(gauge("jvm_memory_used_bytes", nonf))
    # max 가 -1 인 영역(미정)은 합에서 뺀다
    hm = m.agg(target, "jvm_memory_max_bytes", filt=heapf, by="id")
    hsum = sum(_series(d)[-1][1] for d in hm.values() if d and _series(d)[-1][1] > 0) if hm else None
    res["heap_max_bytes"] = hsum or None
    res["_heap_ts"] = _in(_series(m.agg(target, "jvm_memory_used_bytes", filt=heapf).get(None, {})), win, whole=True)
    if not heap:
        missing.append(f"{slot}:jvm_memory_used_bytes")
    # GC
    gsum = delta("jvm_gc_pause_seconds_sum")
    gcnt = delta("jvm_gc_pause_seconds_count")
    dur = win.duration or 1
    res["gc_pause_sec_per_sec"] = rnd(gsum / dur, 5) if gsum is not None else None
    res["gc_count_per_min"] = rnd(gcnt / dur * 60, 2) if gcnt is not None else None
    gmax = gauge("jvm_gc_pause_seconds_max")
    res["gc_pause_max_ms"] = rnd(max(v for _, v in gmax) * 1000, 1) if gmax else None
    alloc = delta("jvm_gc_memory_allocated_bytes_total")
    res["alloc_bytes_delta"] = alloc
    if gsum is None:
        missing.append(f"{slot}:jvm_gc_pause_seconds")
    if alloc is None:
        missing.append(f"{slot}:jvm_gc_memory_allocated_bytes_total")
    # 서버 쪽 요청 수 — actuator 제외, uri 별
    notact = lambda d: not d.get("uri", "").startswith("/actuator")
    reqs = m.agg(target, "http_server_requests_seconds_count", filt=notact, by="uri")
    by_uri = {}
    for uri, d in reqs.items():
        pts = _in(_series(d), win)
        if pts:
            by_uri[uri] = win_increase(pts, win)
    res["server_requests"] = rnd(sum(by_uri.values()), 0) if by_uri else None
    res["server_requests_by_uri"] = {k: rnd(v, 0) for k, v in sorted(by_uri.items(), key=lambda x: -x[1])}
    if not reqs:
        missing.append(f"{slot}:http_server_requests_seconds_count")
    # W1 서버 쪽 지연 · 5xx — (uri, status) 별 요청 수 증가와 누적 버킷 증가. 슬롯을 합쳐 analyze() 가 분위를 낸다
    cnt_us = {}
    for lab, pts in m.data.get(target, {}).get("http_server_requests_seconds_count", {}).items():
        d = dict(lab)
        if not notact(d):
            continue
        p = _in(sorted(pts), win)
        if p:
            k = (d.get("uri", "?"), d.get("status", "?"))
            cnt_us[k] = cnt_us.get(k, 0.0) + win_increase(p, win)
    bk_us = defaultdict(lambda: defaultdict(float))
    for lab, pts in m.data.get(target, {}).get("http_server_requests_seconds_bucket", {}).items():
        d = dict(lab)
        if not notact(d) or "le" not in d:
            continue
        p = _in(sorted(pts), win)
        if p:
            bk_us[(d.get("uri", "?"), d.get("status", "?"))][d["le"]] += increase(p)
    res["_http_counts"] = cnt_us
    res["_http_buckets"] = {k: dict(v) for k, v in bk_us.items()}
    if reqs and not bk_us:
        missing.append(f"{slot}:http_server_requests_seconds_bucket")
    # Redis 명령 지연(lettuce) — 있으면 평균
    lsum = None
    lcnt = None
    for n in m.data.get(target, {}):
        if n.startswith("lettuce_") and n.endswith("_seconds_sum"):
            lsum = (lsum or 0) + (delta(n) or 0)
        if n.startswith("lettuce_") and n.endswith("_seconds_count"):
            lcnt = (lcnt or 0) + (delta(n) or 0)
    res["lettuce_mean_ms"] = rnd(lsum / lcnt * 1000, 3) if lsum is not None and lcnt else None
    res["lettuce_commands"] = rnd(lcnt, 0) if lcnt else None
    # 실행기 큐 · 스레드 · 파일 · CPU · 재시작
    eq = m.agg(target, "executor_queued_tasks", by="name")
    res["executor_queued_max"] = {k or "-": max((v for _, v in _in(_series(d), win)), default=None) for k, d in eq.items()} or None
    ea = m.agg(target, "executor_active_threads", by="name")
    res["executor_active_max"] = {k or "-": max((v for _, v in _in(_series(d), win)), default=None) for k, d in ea.items()} or None
    # 단계별 집계용 — JVM 사용 메모리(힙 + 힙 밖, 모든 영역 합). 슬롯 컨테이너 RSS 는 원천이 없다(cAdvisor 없음)
    res["_jvm_ts"] = _in(_series(m.agg(target, "jvm_memory_used_bytes").get(None, {})), win, whole=True)
    th = gauge("jvm_threads_live_threads")
    res["threads_live"] = gstats(th)
    res["open_files"] = gstats(gauge("process_files_open_files"))
    pc = gauge("process_cpu_usage")
    res["process_cpu_pct"] = {"max": rnd(max(v for _, v in pc) * 100, 1), "mean": rnd(sum(v for _, v in pc) / len(pc) * 100, 1)} if pc else None
    st = gauge("process_start_time_seconds")
    res["restarts"] = len({round(v) for _, v in st}) - 1 if st else None
    res["_start_ts"] = _in(_series(m.agg(target, "process_start_time_seconds").get(None, {})), win, whole=True)
    cm = gauge("container_memory_usage_bytes")
    res["container_memory_max_bytes"] = max(v for _, v in cm) if cm else None
    return res


# ---------------------------------------------------------------- PostgreSQL · Redis · Nginx

def _first(m, target, names):
    for n in names:
        if m.has(target, n):
            return n
    return None


def postgres(m: NodeMetrics, win, missing):
    t = "postgres"
    res = {}
    dur = win.duration or 1

    def cdelta(name, filt=None):
        if not m.has(t, name):
            return None
        pts = _in(_series(m.agg(t, name, filt=filt).get(None, {})), win)
        return win_increase(pts, win) if pts else None

    def gmax(name, filt=None):
        pts = _in(_series(m.agg(t, name, filt=filt).get(None, {})), win)
        return max((v for _, v in pts), default=None)

    hit = cdelta("pg_stat_database_blks_hit")
    read = cdelta("pg_stat_database_blks_read")
    res["hit_ratio_pct"] = rnd(100.0 * hit / (hit + read), 3) if hit is not None and read is not None and (hit + read) > 0 else None
    res["blks_read_delta"] = read
    for k, n in (("xact_commit", "pg_stat_database_xact_commit"), ("xact_rollback", "pg_stat_database_xact_rollback"),
                 ("deadlocks", "pg_stat_database_deadlocks"), ("temp_bytes", "pg_stat_database_temp_bytes"),
                 ("temp_files", "pg_stat_database_temp_files"), ("conflicts", "pg_stat_database_conflicts"),
                 ("blk_read_time", "pg_stat_database_blk_read_time")):
        res[k + "_delta"] = cdelta(n)
        if res[k + "_delta"] is None:
            missing.append(f"{m.node}:{n}")
    res["xact_per_sec"] = rnd(res["xact_commit_delta"] / dur, 2) if res.get("xact_commit_delta") is not None else None
    ck = 0.0
    found = False
    for n in ("pg_stat_bgwriter_checkpoints_timed_total", "pg_stat_bgwriter_checkpoints_req_total",
              "pg_stat_checkpointer_num_timed_total", "pg_stat_checkpointer_num_requested_total"):
        d = cdelta(n)
        if d is not None:
            ck += d
            found = True
    res["checkpoints_delta"] = ck if found else None
    if not found:
        missing.append(f"{m.node}:pg_stat_*checkpoint*")
    # WAL 바이트 — 늘기만 하는 이름을 정한 순서로 하나만 쓴다. 이름 패턴으로 넓게 찾으면 오르내리는 게이지
    # (pg_replication_slot_safe_wal_size_bytes · pg_wal_size_bytes)가 걸려 win_increase 가 감소를 재시작으로 보고
    # 값을 더해 부풀었다(#445). 라벨 줄(슬롯 · 대기 노드)은 합치지 않고 줄마다 증가량을 구해 가장 큰 값
    wal = None
    wn = _first(m, t, ("pg_stat_wal_wal_bytes_total", "pg_stat_wal_wal_bytes",
                       "pg_stat_replication_pg_current_wal_lsn_bytes"))
    if wn:
        for pts in m.data[t][wn].values():
            p = _in(pts, win)
            if p:
                d = win_increase(p, win)
                wal = d if wal is None else max(wal, d)
    res["wal_metric"] = wn if wal is not None else None
    res["wal_bytes_delta"] = wal
    # 연결 상태별 최대
    sname = _first(m, t, ("rental_pg_sessions_count", "pg_stat_activity_count"))
    states = {}
    if sname:
        for st, d in m.agg(t, sname, by="state").items():
            pts = _in(_series(d), win)
            states[st or "-"] = max((v for _, v in pts), default=None)
    else:
        missing.append(f"{m.node}:rental_pg_sessions_count|pg_stat_activity_count")
    res["connections_by_state_max"] = states or None
    nb = gmax("pg_stat_database_numbackends")
    res["numbackends_max"] = nb
    nbs = _in(_series(m.agg(t, "pg_stat_database_numbackends").get(None, {})), win, whole=True)
    res["numbackends_start"] = nbs[0][1] if nbs else None
    mc = _first(m, t, ("rental_pg_max_connections", "pg_settings_max_connections"))
    res["max_connections"] = gmax(mc) if mc else None
    res["max_connections_source"] = mc or "기본값 48(설계)"
    if res["max_connections"] is None:
        res["max_connections"] = PG_MAX_CONN_DEFAULT
    lag = _first(m, t, ("pg_replication_lag_seconds", "pg_replication_lag"))
    res["replication_lag_max_sec"] = gmax(lag) if lag else None
    res["max_tx_duration_sec"] = gmax("pg_stat_activity_max_tx_duration")
    res["blocked_sessions_max"] = gmax("rental_pg_blocked_sessions")
    # DB 재시작 — postmaster 시작 시각이 회차 중에 바뀌었나([7.8] 프로세스 종료 · DB). exporter 의 postmaster 수집기 지표라
    # 없으면 None 으로 두고 missing 에 올리지 않는다(판정에 꼭 필요한 지표가 아니다)
    ps = _in(_series(m.agg(t, "pg_postmaster_start_time_seconds").get(None, {})), win, whole=True)
    res["restarts"] = len({round(v) for _, v in ps}) - 1 if ps else None
    cm = gmax("container_memory_usage_bytes")
    res["container_memory_max_bytes"] = cm
    return res


def redis(m: NodeMetrics, win, missing):
    t = "redis"

    def d(name):
        pts = _in(_series(m.agg(t, name).get(None, {})), win)
        return win_increase(pts, win) if pts else None

    def g(name):
        pts = _in(_series(m.agg(t, name).get(None, {})), win)
        return max((v for _, v in pts), default=None)

    hits, miss = d("redis_keyspace_hits_total"), d("redis_keyspace_misses_total")
    ev = d("redis_evicted_keys_total")
    res = {
        "node": m.node,
        "hit_ratio_pct": rnd(100.0 * hits / (hits + miss), 2) if hits is not None and miss is not None and hits + miss > 0 else None,
        "evicted_per_sec": rnd(ev / (win.duration or 1), 3) if ev is not None else None,
        "memory_used_max_bytes": g("redis_memory_used_bytes"),
        "memory_max_bytes": g("redis_memory_max_bytes"),
        "connected_clients_max": g("redis_connected_clients"),
        "_mem_ts": _in(_series(m.agg(t, "redis_memory_used_bytes").get(None, {})), win, whole=True),
    }
    if hits is None:
        missing.append(f"{m.node}:redis_keyspace_hits_total")
    return res


def nginx(m: NodeMetrics, win, missing):
    t = "nginx"
    act = _in(_series(m.agg(t, "nginx_connections_active").get(None, {})), win)
    req = _in(_series(m.agg(t, "nginx_http_requests_total").get(None, {})), win)
    if not act:
        missing.append(f"{m.node}:nginx_connections_active")
    return {"node": m.node, "connections_active_max": max((v for _, v in act), default=None),
            "requests_delta": win_increase(req, win) if req else None}


# ---------------------------------------------------------------- 묶음

def _between(pts, lo, hi):
    return [(t, v) for t, v in pts if lo <= t <= hi]


def _step_node(o, steps, acc):
    """단계마다 노드 CPU 평균 · 최대 — node_os 의 시계열(_cpu_ts)에서. 그 시계열의 점 하나는 앞 긁기부터 그 긁기까지의
    비율이라 간격 가운데(시각 − 간격/2)가 단계 안인 점만 센다 — 단계 경계의 점이 앞 단계 몫을 끌고 오지 않게."""
    pts = o.get("_cpu_ts") or []
    gaps = sorted(b[0] - a[0] for a, b in zip(pts, pts[1:]))
    half = gaps[len(gaps) // 2] / 2 if gaps else 0.0
    for st in steps:
        v = [x for _, x in _between(pts, st["lo"] + half, st["hi"] + half)]
        if v:
            acc[st["index"]]["nodes"][o["_node"]] = {"cpu_mean": rnd(sum(v) / len(v), 1), "cpu_max": rnd(max(v), 1)}


def _step_slot(m, tg, key, s, steps, acc):
    """단계마다 슬롯의 바쁜 스레드 · JVM 메모리 · 풀(대기 · 활성 · 획득 평균) · 재시작."""
    pools = {}
    for st in steps:
        lo, hi = st["lo"], st["hi"]
        busy = [v for _, v in _between(s.get("_tomcat_ts") or [], lo, hi)]
        jvm = [v for _, v in _between(s.get("_jvm_ts") or [], lo, hi)]
        heap = [v for _, v in _between(s.get("_heap_ts") or [], lo, hi)]
        pool_out = {}
        for pool, p in (s.get("hikari") or {}).items():
            f = pools.setdefault(pool, (lambda x: (lambda d: d.get("pool") == x))(pool))
            pend = [v for _, v in _between(p.get("_pending_ts") or [], lo, hi)]
            act = [v for _, v in _between(p.get("_active_ts") or [], lo, hi)]
            a_sum = _between(_series(m.agg(tg, "hikaricp_connections_acquire_seconds_sum", filt=f).get(None, {})), lo, hi)
            a_cnt = _between(_series(m.agg(tg, "hikaricp_connections_acquire_seconds_count", filt=f).get(None, {})), lo, hi)
            ds, dc = (increase(a_sum), increase(a_cnt)) if a_sum and a_cnt else (None, None)
            pool_out[pool] = {"pending_max": max(pend) if pend else None, "active_max": max(act) if act else None, "max": p.get("max"),
                              "acquire_mean_ms": rnd(ds / dc * 1000, 3) if dc else None}
        # 재시작 — 단계 직전 값부터 단계 안 값까지 시작 시각이 몇 번 바뀌었나
        stp = s.get("_start_ts") or []
        before = [v for t, v in stp if t < lo][-1:]
        vals = before + [v for _, v in _between(stp, lo, hi)]
        restarts = len({round(v) for v in vals}) - 1 if vals else None
        acc[st["index"]]["slots"][key] = {
            "busy_max": max(busy) if busy else None, "busy_mean": rnd(sum(busy) / len(busy), 2) if busy else None,
            "tomcat_max": s.get("tomcat_max"), "jvm_used_max_bytes": max(jvm) if jvm else None,
            "heap_used_max_bytes": max(heap) if heap else None, "heap_max_bytes": s.get("heap_max_bytes"),
            "pools": pool_out, "restarts": restarts}


def server_latency(slots_raw):
    """W1 — 슬롯을 합친 (uri, status) 별 요청 수 · 버킷 증가 → 경로별 p50 · p95(구간 보간) · 5xx 비율.
    application.yml 의 slo 다섯 칸(0.1 · 0.3 · 0.5 · 1 · 3 초) 사이 보간이라 대조용이다 — 판정은 부하 도구(jtl)."""
    cnt = defaultdict(float)
    bk = defaultdict(lambda: defaultdict(float))
    for s in slots_raw:
        for k, v in (s.get("_http_counts") or {}).items():
            cnt[k] += v
        for k, d in (s.get("_http_buckets") or {}).items():
            for le, v in d.items():
                bk[k][le] += v
    if not cnt and not bk:
        return None
    by_uri = defaultdict(lambda: {"count": 0.0, "c5xx": 0.0, "buckets": defaultdict(float), "by_status": {}})
    for (uri, status), c in cnt.items():
        u = by_uri[uri]
        u["count"] += c
        if str(status).startswith("5"):
            u["c5xx"] += c
    for (uri, status), d in bk.items():
        u = by_uri[uri]
        for le, v in d.items():
            u["buckets"][le] += v
        q95 = _hist_quantile(d, 0.95)
        u["by_status"][status] = {"count": rnd(cnt.get((uri, status)), 0), "p95_ms": rnd(q95 * 1000, 1) if q95 is not None else None}
    out = {}
    for uri, u in sorted(by_uri.items(), key=lambda x: -x[1]["count"]):
        q50, q95 = _hist_quantile(u["buckets"], 0.5), _hist_quantile(u["buckets"], 0.95)
        out[uri] = {"count": rnd(u["count"], 0), "p50_ms": rnd(q50 * 1000, 1) if q50 is not None else None,
                    "p95_ms": rnd(q95 * 1000, 1) if q95 is not None else None,
                    "p95_over_3s": bool(q95 is not None and q95 >= 3.0),
                    "route": route_key(uri),          # 엔드포인트 키 — jtl · Nginx · 추적과 같은 키로 잇는다([7.1])
                    "rate_5xx_pct": rnd(100.0 * u["c5xx"] / u["count"], 3) if u["count"] else None,
                    "by_status": u["by_status"]}
    return out


def analyze(metrics_dir: Path, win, tool_requests=None, steps=None):
    """노드 파일 전부를 읽어 자원 요약 · 헤드룸 · G2 시계열을 만든다.
    steps — [{"index", "lo", "hi"}](유닉스 초)를 주면 단계마다의 노드 CPU · 슬롯 자원을 out["steps"] 에 낸다(steps.py 가 쓴다)."""
    missing = []
    out = {"nodes": {}, "slots": {}, "postgres": {}, "redis": None, "nginx": None}
    ts = {}
    steps = [st for st in (steps or []) if st.get("lo") is not None and st.get("hi") is not None]
    step_acc = defaultdict(lambda: {"nodes": {}, "slots": {}})
    slots_raw = []
    files = sorted(metrics_dir.glob("*.prom*")) if metrics_dir.exists() else []
    if not files:
        out["missing_metrics"] = ["metrics/ 폴더 없음"]
        out["headroom"] = []
        out["timeseries"] = {}
        return out
    t0 = win.start
    rel = lambda pts: [[rnd(s - t0, 1), rnd(v, 2)] for s, v in pts] if t0 is not None else []
    for f in files:
        node = canon_node(f.name.split(".prom")[0])
        m = NodeMetrics.load(f, node)
        if "node" in m.targets():
            o = node_os(m, win, missing)
            if o.get("_cpu_ts"):
                ts[f"{node} CPU"] = {"layer": "OS", "group": ("앱노드 CPU" if node.startswith("app") else f"{node} CPU"), "points": rel(o["_cpu_ts"])}
            if o.get("_mem_ts"):
                ts[f"{node} 메모리"] = {"layer": "OS", "group": ("앱노드 메모리" if node.startswith("app") else "DB노드 메모리"), "points": rel(o["_mem_ts"])}
            if o.get("_disk_ts") and node.startswith("db"):
                ts[f"{node} 디스크 util"] = {"layer": "OS", "group": "DB 디스크 util", "points": rel(o["_disk_ts"])}
            if steps:
                o["_node"] = node
                _step_node(o, steps, step_acc)
            out["nodes"][node] = {k: v for k, v in o.items() if not k.startswith("_")}
        for tg in m.targets():
            if tg.startswith("app"):
                s = app_slot(m, tg, win, missing)
                key = f"{node}/{tg}"
                if steps:
                    _step_slot(m, tg, key, s, steps, step_acc)
                slots_raw.append({"_http_counts": s.get("_http_counts"), "_http_buckets": s.get("_http_buckets")})
                if s.get("tomcat_max") and s.get("_tomcat_ts"):
                    ts[f"{key} 스레드"] = {"layer": "앱", "group": "요청 스레드", "points": rel([(a, 100.0 * b / s["tomcat_max"]) for a, b in s["_tomcat_ts"]])}
                if s.get("heap_max_bytes") and s.get("_heap_ts"):
                    ts[f"{key} 힙"] = {"layer": "앱", "group": "힙", "points": rel([(a, 100.0 * b / s["heap_max_bytes"]) for a, b in s["_heap_ts"]])}
                for pool, p in s["hikari"].items():
                    if p.get("max") and p.get("_active_ts"):
                        ts[f"{key} 풀 {pool}"] = {"layer": "경계", "group": f"풀 {pool} 활성", "points": rel([(a, 100.0 * b / p["max"]) for a, b in p["_active_ts"]])}
                    for k in [k for k in p if k.startswith("_")]:
                        p.pop(k)
                out["slots"][key] = {k: v for k, v in s.items() if not k.startswith("_")}
            elif tg == "postgres":
                out["postgres"][node] = postgres(m, win, missing)
            elif tg == "redis":
                r = redis(m, win, missing)
                if r.get("memory_max_bytes") and r.get("_mem_ts"):
                    ts["Redis 메모리"] = {"layer": "캐시", "group": "Redis 메모리", "points": rel([(a, 100.0 * b / r["memory_max_bytes"]) for a, b in r["_mem_ts"]])}
                r.pop("_mem_ts", None)
                out["redis"] = r
            elif tg == "nginx":
                out["nginx"] = nginx(m, win, missing)
        del m
    out["app_totals"] = _app_totals(out["slots"], tool_requests, win)
    out["server_latency"] = server_latency(slots_raw)
    out["headroom"] = headroom(out)
    out["timeseries"] = ts
    out["missing_metrics"] = sorted(set(missing))
    if steps:
        out["steps"] = {str(idx): v for idx, v in sorted(step_acc.items())}   # JSON 키는 글자다 — 처음부터 글자로
    return out


def _app_totals(slots, tool_requests, win):
    if not slots:
        return {}
    alloc = [s["alloc_bytes_delta"] for s in slots.values() if s.get("alloc_bytes_delta") is not None]
    sreq = [s["server_requests"] for s in slots.values() if s.get("server_requests") is not None]
    tot_req = sum(sreq) if sreq else None
    busy = [s["tomcat_busy"]["max"] for s in slots.values() if s.get("tomcat_busy")]
    tmax = [s["tomcat_max"] for s in slots.values() if s.get("tomcat_max")]
    pools = defaultdict(lambda: {"pending_max": None, "active_max": None, "max_per_slot": None, "slots": 0,
                                 "acquire_mean_ms": [], "acquire_p95_ms": [], "acquire_max_ms": [], "usage_mean_ms": [], "timeouts": 0})
    for s in slots.values():
        for name, p in (s.get("hikari") or {}).items():
            a = pools[name]
            a["slots"] += 1
            for k in ("pending_max", "active_max"):
                if p.get(k) is not None:
                    a[k] = max(a[k] or 0, p[k])
            if p.get("max") is not None:
                a["max_per_slot"] = p["max"]
            for k in ("acquire_mean_ms", "acquire_p95_ms", "acquire_max_ms", "usage_mean_ms"):
                if p.get(k) is not None:
                    a[k].append(p[k])
            a["timeouts"] += p.get("timeouts") or 0
    pool_out = {}
    for name, a in pools.items():
        pool_out[name] = {
            "pending_max": a["pending_max"], "active_max": a["active_max"], "max_per_slot": a["max_per_slot"], "slots": a["slots"],
            "acquire_mean_ms": rnd(max(a["acquire_mean_ms"]), 3) if a["acquire_mean_ms"] else None,
            "acquire_p95_ms": rnd(max(a["acquire_p95_ms"]), 3) if a["acquire_p95_ms"] else None,
            "acquire_max_ms": rnd(max(a["acquire_max_ms"]), 3) if a["acquire_max_ms"] else None,
            "usage_mean_ms": rnd(max(a["usage_mean_ms"]), 3) if a["usage_mean_ms"] else None,
            "timeouts": a["timeouts"],
        }
    heap = [s["heap_used"]["max"] for s in slots.values() if s.get("heap_used")]
    nonheap = [s["nonheap_used"]["max"] for s in slots.values() if s.get("nonheap_used")]
    gcp = [s["gc_pause_sec_per_sec"] for s in slots.values() if s.get("gc_pause_sec_per_sec") is not None]
    gcc = [s["gc_count_per_min"] for s in slots.values() if s.get("gc_count_per_min") is not None]
    lm = [s["lettuce_mean_ms"] for s in slots.values() if s.get("lettuce_mean_ms") is not None]
    res = {
        "server_requests": tot_req,
        "server_tps": rnd(tot_req / win.duration, 3) if tot_req is not None and win.duration else None,
        "server_over_tool": rnd(tot_req / tool_requests, 4) if tot_req and tool_requests else None,
        "alloc_bytes_delta": sum(alloc) if alloc else None,
        "alloc_bytes_per_request": rnd(sum(alloc) / tool_requests, 0) if alloc and tool_requests else None,
        "tomcat_busy_max": max(busy) if busy else None,
        "tomcat_busy_sum_max": sum(busy) if busy else None,
        "tomcat_max_total": sum(tmax) if tmax else None,
        "tomcat_queue_suspected": any(s.get("tomcat_queue_suspected") for s in slots.values()),
        "heap_used_max_bytes": max(heap) if heap else None,
        "nonheap_used_max_bytes": max(nonheap) if nonheap else None,
        "gc_pause_sec_per_sec_max": max(gcp) if gcp else None,
        "gc_count_per_min_max": max(gcc) if gcc else None,
        "lettuce_mean_ms": rnd(sum(lm) / len(lm), 3) if lm else None,
        "pools": pool_out,
        "restarts": sum(s["restarts"] for s in slots.values() if s.get("restarts")),
        "threads_live_max": max((s["threads_live"]["max"] for s in slots.values() if s.get("threads_live")), default=None),
        "threads_live_start": max((s["threads_live"]["start"] for s in slots.values() if s.get("threads_live")), default=None),
        "heap_start_bytes": max((s["heap_used"]["start"] for s in slots.values() if s.get("heap_used")), default=None),
        "open_files_max": max((s["open_files"]["max"] for s in slots.values() if s.get("open_files")), default=None),
    }
    if tot_req:
        for s in slots.values():
            if s.get("server_requests") is not None:
                s["request_share_pct"] = rnd(100.0 * s["server_requests"] / tot_req, 1)
    return res


def headroom(out):
    """자원별 최대값과 상한 — [7.4]. 상한을 모르면 headroom 은 None."""
    rows = []

    def add(layer, res, limit, peak, unit="", note=None):
        hr = rnd(100.0 * (limit - peak) / limit, 1) if limit and peak is not None else None
        rows.append({"layer": layer, "resource": res, "limit": limit, "peak": rnd(peak, 3) if peak is not None else None,
                     "unit": unit, "headroom_pct": hr, "note": note})

    for node, o in sorted(out["nodes"].items()):
        is_db = node.startswith("db")
        cp = (o.get("cpu_pct") or {}).get("max")
        add("OS", f"{node} CPU", 100.0, cp, "%", f"{DB_VCPU if is_db else APP_VCPU} vCPU")
        if o.get("load_per_vcpu"):
            add("OS", f"{node} load÷vCPU", 1.0, o["load_per_vcpu"]["max"])
        if o.get("mem_total_bytes") and o.get("mem_available_min_bytes") is not None:
            add("OS", f"{node} 메모리 사용", o["mem_total_bytes"], o["mem_total_bytes"] - o["mem_available_min_bytes"], "B")
        add("OS", f"{node} 디스크 util", 100.0, o.get("disk_util_pct_max"), "%")
        e_type, guess = ebs_type(is_db, o.get("mem_total_bytes"))
        e_iops, e_bps = EBS_BASELINE[e_type]
        e_note = f"{e_type}{' (추정)' if guess else ''} EBS 기준선"
        add("OS", f"{node} 디스크 IOPS", e_iops, (o.get("disk_iops") or {}).get("max"), "IOPS", e_note)
        add("OS", f"{node} 디스크 처리량", e_bps, (o.get("disk_bps") or {}).get("max"), "B/s", e_note)
        if o.get("conntrack"):
            add("OS", f"{node} conntrack", o["conntrack"].get("limit") or CONNTRACK_DEFAULT, o["conntrack"]["max"])
        add("OS", f"{node} TCP 대기열 넘침", 0, o.get("listen_overflows_delta"), "회")
        add("OS", f"{node} OOM", 0, o.get("oom_kills_delta"), "회")
    for key, s in sorted(out["slots"].items()):
        if s.get("tomcat_busy"):
            add("앱", f"{key} 요청 스레드", s.get("tomcat_max"), s["tomcat_busy"]["max"])
        if s.get("heap_used"):
            add("앱", f"{key} 힙", s.get("heap_max_bytes"), s["heap_used"]["max"], "B")
        for pool, p in (s.get("hikari") or {}).items():
            add("경계", f"{key} 풀 {pool} (활성)", p.get("max"), p.get("active_max"))
    for node, p in sorted(out["postgres"].items()):
        nb = p.get("numbackends_max")
        add("DB", f"{node} 커넥션", p.get("max_connections"), nb)
        if p.get("container_memory_max_bytes"):
            add("DB", f"{node} 컨테이너 메모리", None, p["container_memory_max_bytes"], "B")
    r = out.get("redis")
    if r:
        add("캐시", "Redis 메모리", r.get("memory_max_bytes") or None, r.get("memory_used_max_bytes"), "B")
    return rows
