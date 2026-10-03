"""가짜 회차 폴더 생성기 — 입력 형식 계약과 똑같은 모양으로 작은 회차 둘(_fixture_R01 · _fixture_R02)을 만든다.

    python -m analyze.fixtures            (측정 폴더에서)
값은 그럴듯하게만 만든 것이다. 실측이 아니다 — 시험 · 보고서 모양 확인용.
"""
from __future__ import annotations

import gzip
import json
import random
import shutil
from datetime import datetime, timezone, timedelta

from .util import RESULTS_DIR

KST = timezone(timedelta(hours=9))
ENDPOINTS = [
    # 레이블, 메서드, 경로(실제), 경로(추적 http.route), 비중, 기준 지연(ms), 질의 수, store 수
    ("district-counts", "GET", "/api/map/district-counts", "/api/map/district-counts", 0.30, 40, 1, 1),
    ("map-clusters", "GET", "/api/map/clusters?swLat=37.4&neLat=37.6", "/api/map/clusters", 0.25, 120, 2, 1),
    ("property-detail", "GET", "/api/properties/{n}", "/api/properties/{id}", 0.20, 60, 3, 2),
    ("risk", "GET", "/api/properties/{n}/risk", "/api/properties/{propertyId}/risk", 0.15, 90, 12, 1),
    ("login", "POST", "/api/auth/login", "/api/auth/login", 0.10, 180, 2, 3),
]
DURATION = 300
WARMUP = 30


def _hex(rng, n):
    return "".join(rng.choice("0123456789abcdef") for _ in range(n))


def make_round(name, start, speed=1.0, changed="", seed=1, lead=0, trail=0):
    """lead · trail — 기록(record start/stop)보다 JMeter 가 늦게 시작하고 일찍 끝나는 초. 통계 구간은 JMeter 가 정한다."""
    rng = random.Random(seed)
    d = RESULTS_DIR / name
    if d.exists():
        shutil.rmtree(d)
    for sub in ("jmeter", "metrics", "db", "pre", "post", "nginx", "traces", "profiler", "gen"):
        (d / sub).mkdir(parents=True, exist_ok=True)
    end = start + timedelta(seconds=DURATION)
    iso = lambda dt: dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"
    meta = {"round": name, "start_utc": iso(start), "end_utc": iso(end), "interval_s": 5, "generator": "LOAD-01 (가짜)",
            "nodes": ["app01", "app02", "db01", "db02"],
            "node_clock_utc": {"app01": iso(start), "app02": None, "db01": iso(start), "db02": None}}
    (d / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    rnd_ = {"scenario": "S1", "changed": changed, "commit": "abc1234" if not changed else "def5678", "migration": "V42",
            "warmup_sec": WARMUP, "notes": "가짜 회차(fixtures.py)", "kind": "mix"}
    (d / "round.json").write_text(json.dumps(rnd_, ensure_ascii=False, indent=2), encoding="utf-8")
    t0 = start.timestamp()

    # ---- JMeter · Nginx · 추적 — 같은 요청을 세 곳에 남긴다
    jtl = ["timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,"
           "grpThreads,allThreads,URL,Latency,IdleTime,Connect"]
    ngx = []
    spans_lines = []
    reqs = []
    j0 = t0 + lead
    t = j0 + 0.05
    while t < t0 + DURATION - trail - 1:
        el = (t - j0)
        threads = 2 + int(el / 60) * 2
        rate = threads * 1.0  # 초당 요청
        t += rng.expovariate(rate)
        ep = rng.choices(ENDPOINTS, weights=[e[4] for e in ENDPOINTS])[0]
        reqs.append((t, ep, threads))
    for i, (t, ep, threads) in enumerate(reqs):
        label, method, path, route, _, base, nq, ns = ep
        pid = rng.randint(1, 5_000_000)
        real = path.replace("{n}", str(pid))
        elapsed = max(2, int(rng.lognormvariate(0, 0.45) * base * speed))
        code = "200"
        ok = True
        r = rng.random()
        if r < 0.002:
            code, ok = "500", False
        elif r < 0.003:
            code, ok = "Non HTTP response code: java.net.SocketTimeoutException", False
        nbytes = {"district-counts": 2400, "map-clusters": 18000, "property-detail": 3200, "risk": 1500, "login": 600}[label]
        nbytes = int(nbytes * rng.uniform(0.8, 1.2))
        url = "https://3.36.74.44" + real
        jtl.append(f"{int(t * 1000)},{elapsed},{label},{code},{'OK' if ok else 'ERR'},S1 1-{i % threads + 1},text,{str(ok).lower()},,"
                   f"{nbytes},300,{threads},{threads},{url},{max(elapsed - 1, 1)},0,{rng.randint(0, 2)}")
        if not code.isdigit():
            continue
        rt = elapsed / 1000 - 0.002
        urt = rt - 0.001 - rng.random() * 0.001
        slot = rng.choice(["10.0.1.10:8081", "10.0.1.10:8082", "10.0.1.11:8081", "10.0.1.11:8082"])
        tl = datetime.fromtimestamp(t + rt, KST).strftime("%d/%b/%Y:%H:%M:%S %z")
        ngx.append(f'1.2.3.4 - - [{tl}] "{method} {real} HTTP/1.1" {code} {nbytes} "-" "Apache-HttpClient/4.5.14" '
                   f'upstream={slot} rt={rt:.3f} rid={_hex(rng, 32)} uct=0.001 urt={urt:.3f}')
        # 추적 — 서버 구간 · 서비스 메서드 · JDBC · store · 커밋
        tid = _hex(rng, 32)
        s0 = int((t + 0.002) * 1e9)
        sdur = int(urt * 1e9 * 0.95)
        srv = _hex(rng, 16)
        svc = _hex(rng, 16)
        cls = {"district-counts": "MapQueryService", "map-clusters": "MapQueryService", "property-detail": "PropertyQueryService",
               "risk": "RiskQueryService", "login": "AuthService"}[label]
        spans = [{"traceId": tid, "spanId": srv, "parentSpanId": "", "name": f"{method} {route}", "kind": 2,
                  "startTimeUnixNano": str(s0), "endTimeUnixNano": str(s0 + sdur),
                  "attributes": [{"key": "http.route", "value": {"stringValue": route}},
                                 {"key": "http.request.method", "value": {"stringValue": method}},
                                 {"key": "http.response.status_code", "value": {"intValue": code}}], "status": {}}]
        m0 = s0 + int(sdur * 0.05)
        mdur = int(sdur * 0.85)
        methods = [{"traceId": tid, "spanId": svc, "parentSpanId": srv, "name": f"{cls}.handle", "kind": 1,
                    "startTimeUnixNano": str(m0), "endTimeUnixNano": str(m0 + mdur),
                    "attributes": [{"key": "code.namespace", "value": {"stringValue": f"com.duri.rentalplatform.domain.x.service.{cls}"}},
                                   {"key": "code.function", "value": {"stringValue": "handle"}}]}]
        cur = m0 + int(mdur * 0.05)
        step = int(mdur * 0.6 / max(nq + 1, 1))
        db_host = "db-02" if method == "GET" and rng.random() < 0.7 else "db-01"
        for q in range(nq):
            stmt = "SELECT * FROM property WHERE id = ?" if q % 2 == 0 else "SELECT grade FROM risk_analysis WHERE property_id = ?"
            spans.append({"traceId": tid, "spanId": _hex(rng, 16), "parentSpanId": svc, "name": "SELECT rental.property", "kind": 3,
                          "startTimeUnixNano": str(cur), "endTimeUnixNano": str(cur + int(step * 0.8)),
                          "attributes": [{"key": "db.system.name", "value": {"stringValue": "postgresql"}},
                                         {"key": "db.query.text", "value": {"stringValue": stmt}},
                                         {"key": "server.address", "value": {"stringValue": db_host}}]})
            cur += step
        spans.append({"traceId": tid, "spanId": _hex(rng, 16), "parentSpanId": svc, "name": "COMMIT", "kind": 3,
                      "startTimeUnixNano": str(cur), "endTimeUnixNano": str(cur + 200_000),
                      "attributes": [{"key": "db.system.name", "value": {"stringValue": "postgresql"}},
                                     {"key": "db.operation.name", "value": {"stringValue": "COMMIT"}},
                                     {"key": "server.address", "value": {"stringValue": db_host}}]})
        cur += 300_000
        for k in range(ns):
            spans.append({"traceId": tid, "spanId": _hex(rng, 16), "parentSpanId": svc, "name": "TokenStore.get", "kind": 1,
                          "startTimeUnixNano": str(cur), "endTimeUnixNano": str(cur + 400_000),
                          "attributes": [{"key": "code.namespace", "value": {"stringValue": "com.duri.rentalplatform.domain.auth.store.TokenStore"}},
                                         {"key": "code.function", "value": {"stringValue": "get"}}]})
            cur += 500_000
        inst = {"10.0.1.10:8081": "app-01-1", "10.0.1.10:8082": "app-01-2", "10.0.1.11:8081": "app-02-1", "10.0.1.11:8082": "app-02-2"}[slot]
        req = {"resourceSpans": [
            {"resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "rental-app"}},
                                         {"key": "service.instance.id", "value": {"stringValue": inst}}]},
             "scopeSpans": [{"scope": {"name": "io.opentelemetry.tomcat-10.0"}, "spans": spans[:1]},
                            {"scope": {"name": "io.opentelemetry.jdbc"}, "spans": [x for x in spans[1:] if x["kind"] == 3]},
                            {"scope": {"name": "io.opentelemetry.methods"}, "spans": methods + [x for x in spans[1:] if x["kind"] == 1]}]}]}
        spans_lines.append(json.dumps(req, separators=(",", ":")))
    # 요청 밖 구간 — 배치 하나
    bt = int((t0 + 100) * 1e9)
    btid = _hex(rng, 32)
    spans_lines.append(json.dumps({"resourceSpans": [{"resource": {"attributes": [{"key": "service.instance.id", "value": {"stringValue": "app-01-1"}}]},
                                                      "scopeSpans": [{"scope": {"name": "io.opentelemetry.methods"}, "spans": [
                                                          {"traceId": btid, "spanId": "b1", "parentSpanId": "", "name": "RefreshScheduler.run", "kind": 1,
                                                           "startTimeUnixNano": str(bt), "endTimeUnixNano": str(bt + 2_000_000_000),
                                                           "attributes": [{"key": "code.namespace", "value": {"stringValue": "com.duri.rentalplatform.batch.RefreshScheduler"}}]}]}]}]}))
    # SSE 연결 — 연결 시간이 응답 시간이라 통계에서 빠져야 한다
    for k in range(3):
        st = int((t0 + lead + 60 + k) * 1e9)
        spans_lines.append(json.dumps({"resourceSpans": [{"resource": {"attributes": [{"key": "service.instance.id", "value": {"stringValue": "app-01-1"}}]},
                                                          "scopeSpans": [{"scope": {"name": "io.opentelemetry.tomcat-10.0"}, "spans": [
                                                              {"traceId": _hex(rng, 32), "spanId": "sse1", "parentSpanId": "", "name": "GET /api/notifications/stream",
                                                               "kind": 2, "startTimeUnixNano": str(st), "endTimeUnixNano": str(st + 90_000_000_000),
                                                               "attributes": [{"key": "http.route", "value": {"stringValue": "/api/notifications/stream"}}]}]}]}]}))
    # Redis 를 쓰는 store 밖 클래스(중첩) · 한 추적에서 120번 불리는 메서드 — POST /api/wishlist 한 건
    wt = int((t0 + lead + 100) * 1e9)
    wtid = _hex(rng, 32)
    ws = [{"traceId": wtid, "spanId": "w0", "parentSpanId": "", "name": "POST /api/me/wishlist", "kind": 2,
           "startTimeUnixNano": str(wt), "endTimeUnixNano": str(wt + 50_000_000),
           "attributes": [{"key": "http.route", "value": {"stringValue": "/api/me/wishlist"}}]},
          {"traceId": wtid, "spanId": "w1", "parentSpanId": "w0", "name": "DistributedLockAspect$Around.lock", "kind": 1,
           "startTimeUnixNano": str(wt + 1_000_000), "endTimeUnixNano": str(wt + 4_000_000),
           "attributes": [{"key": "code.namespace", "value": {"stringValue": "com.duri.rentalplatform.common.lock.DistributedLockAspect$Around"}}]}]
    for k in range(120):
        a0 = wt + 5_000_000 + k * 300_000
        ws.append({"traceId": wtid, "spanId": f"r{k}", "parentSpanId": "w0", "name": "CodeConverter.toName", "kind": 1,
                   "startTimeUnixNano": str(a0), "endTimeUnixNano": str(a0 + 100_000),
                   "attributes": [{"key": "code.namespace", "value": {"stringValue": "com.duri.rentalplatform.domain.x.service.CodeConverter"}}]})
    spans_lines.append(json.dumps({"resourceSpans": [{"resource": {"attributes": [{"key": "service.instance.id", "value": {"stringValue": "app-01-1"}}]},
                                                      "scopeSpans": [{"scope": {"name": "io.opentelemetry.methods"}, "spans": ws}]}]}))
    (d / "jmeter" / "result.jtl").write_text("\n".join(jtl) + "\n", encoding="utf-8")
    (d / "nginx" / "access.log").write_text("\n".join(ngx) + "\n", encoding="utf-8")
    # 마지막 줄은 쓰다 만 줄 — 수집 중에 끊긴 모양
    (d / "traces" / "spans.jsonl").write_text("\n".join(spans_lines) + "\n" + spans_lines[0][:80], encoding="utf-8")
    nreq = len(reqs)

    # ---- 노드 지표 — 5초 긁기
    def scrape_series(node, targets):
        out = []
        cpu_acc = {c: {"user": 1000.0, "system": 300.0, "iowait": 20.0, "steal": 5.0, "idle": 50000.0} for c in ("0", "1")}
        cnt = {"req": 0.0, "gc": 0.0, "gcn": 0.0, "alloc": 1e9, "hit": 1e6, "read": 1e4, "commit": 1e5, "io": 100.0,
               "rd": 1e4, "wr": 2e4, "rb": 1e8, "wb": 2e8, "acq_s": 0.0, "acq_c": 0.0, "lo": 3.0, "chk": 10.0, "brt": 500.0,
               "rx": 1e9, "tx": 1e9, "hits": 5e5, "miss": 1e4, "lsum": 0.0, "lcnt": 0.0}
        ts = t0 - 10
        while ts <= t0 + DURATION + 5:
            el = max(ts - t0, 0)
            load = min(1.0, 0.2 + el / DURATION) * (1.0 if speed >= 1 else 0.85)
            for target in targets:
                out.append(f"# SCRAPE {ts:.3f} {target}")
                if target == "node":
                    busy = (0.75 if node.startswith("db") else 0.55) * load
                    for c, acc in cpu_acc.items():
                        acc["user"] += 5 * busy * 0.75
                        acc["system"] += 5 * busy * 0.2
                        acc["iowait"] += 5 * busy * 0.03
                        acc["steal"] += 5 * busy * 0.02
                        acc["idle"] += 5 * (1 - busy)
                        for m, v in acc.items():
                            out.append(f'node_cpu_seconds_total{{cpu="{c}",mode="{m}"}} {v:.2f}')
                    out.append("# HELP node_load1 1m load average.")
                    out.append("# TYPE node_load1 gauge")
                    out.append(f"node_load1 {2.4 * load:.2f}")
                    out.append(f"node_memory_MemAvailable_bytes {(1.2e9 - 4e8 * load):.0f}")
                    out.append("node_memory_MemTotal_bytes 2.0e9")
                    cnt["io"] += 5 * 0.3 * load
                    cnt["rd"] += 5 * 200 * load
                    cnt["wr"] += 5 * 100 * load
                    cnt["rb"] += 5 * 4e6 * load
                    cnt["wb"] += 5 * 2e6 * load
                    out.append(f'node_disk_io_time_seconds_total{{device="nvme0n1"}} {cnt["io"]:.3f}')
                    out.append('node_disk_io_time_seconds_total{{device="loop0"}} 1')
                    out.append(f'node_disk_reads_completed_total{{device="nvme0n1"}} {cnt["rd"]:.0f}')
                    out.append(f'node_disk_writes_completed_total{{device="nvme0n1"}} {cnt["wr"]:.0f}')
                    out.append(f'node_disk_read_bytes_total{{device="nvme0n1"}} {cnt["rb"]:.0f}')
                    out.append(f'node_disk_written_bytes_total{{device="nvme0n1"}} {cnt["wb"]:.0f}')
                    cnt["rx"] += 5 * 3e6 * load
                    cnt["tx"] += 5 * 4e6 * load
                    out.append(f'node_network_receive_bytes_total{{device="ens5"}} {cnt["rx"]:.0f}')
                    out.append(f'node_network_transmit_bytes_total{{device="ens5"}} {cnt["tx"]:.0f}')
                    out.append('node_network_receive_bytes_total{{device="lo"}} 1e12')
                    out.append(f'node_netstat_TcpExt_ListenOverflows {cnt["lo"]:.0f}')
                    out.append(f"node_sockstat_TCP_tw {int(300 * load)}")
                    out.append(f"node_nf_conntrack_entries {int(2000 + 3000 * load)}")
                    out.append("node_nf_conntrack_entries_limit 65536")
                    out.append("node_vmstat_oom_kill 0")
                elif target.startswith("app"):
                    cnt["req"] += 5 * 25 * load
                    cnt["gc"] += 5 * 0.004 * load
                    cnt["gcn"] += 5 * 0.2 * load
                    cnt["alloc"] += 5 * 5e7 * load
                    cnt["acq_s"] += 5 * 25 * load * 0.0004
                    cnt["acq_c"] += 5 * 25 * load
                    cnt["lsum"] += 5 * 30 * load * 0.0006
                    cnt["lcnt"] += 5 * 30 * load
                    out.append(f"tomcat_threads_busy_threads {int(3 + 25 * load)}")
                    out.append("tomcat_threads_config_max_threads 50")
                    for pool, mx in (("primary", 8), ("replica", 2)):
                        out.append(f'hikaricp_connections_pending{{pool="{pool}"}} {1 if load > 0.9 and pool == "primary" else 0}')
                        out.append(f'hikaricp_connections_active{{pool="{pool}"}} {min(mx, int(mx * load + 1))}')
                        out.append(f'hikaricp_connections_max{{pool="{pool}"}} {mx}')
                        out.append(f'hikaricp_connections_acquire_seconds_sum{{pool="{pool}"}} {cnt["acq_s"]:.6f}')
                        out.append(f'hikaricp_connections_acquire_seconds_count{{pool="{pool}"}} {cnt["acq_c"]:.0f}')
                        out.append(f'hikaricp_connections_acquire_seconds_max{{pool="{pool}"}} {0.002 + 0.01 * load:.4f}')
                        out.append(f'hikaricp_connections_usage_seconds_sum{{pool="{pool}"}} {cnt["acq_c"] * 0.02:.4f}')
                        out.append(f'hikaricp_connections_usage_seconds_count{{pool="{pool}"}} {cnt["acq_c"]:.0f}')
                        out.append(f'hikaricp_connections_timeout_total{{pool="{pool}"}} 0')
                    out.append(f'jvm_memory_used_bytes{{area="heap",id="G1 Eden Space"}} {1e8 + 8e7 * load:.0f}')
                    out.append(f'jvm_memory_used_bytes{{area="heap",id="G1 Old Gen"}} {1.2e8:.0f}')
                    out.append(f'jvm_memory_used_bytes{{area="nonheap",id="Metaspace"}} {1.4e8:.0f}')
                    out.append('jvm_memory_max_bytes{{area="heap",id="G1 Eden Space"}} -1')
                    out.append(f'jvm_memory_max_bytes{{area="heap",id="G1 Old Gen"}} {4.0e8:.0f}')
                    out.append(f'jvm_gc_pause_seconds_sum{{action="end of minor GC",cause="G1 Evacuation Pause",gc="G1 Young Generation"}} {cnt["gc"]:.5f}')
                    out.append(f'jvm_gc_pause_seconds_count{{action="end of minor GC",cause="G1 Evacuation Pause",gc="G1 Young Generation"}} {cnt["gcn"]:.0f}')
                    out.append('jvm_gc_pause_seconds_max{{action="end of minor GC",cause="G1 Evacuation Pause",gc="G1 Young Generation"}} 0.012')
                    out.append(f"jvm_gc_memory_allocated_bytes_total {cnt['alloc']:.0f}")
                    out.append(f"jvm_threads_live_threads {60 + int(20 * load)}")
                    out.append(f'http_server_requests_seconds_count{{method="GET",outcome="SUCCESS",status="200",uri="/api/map/clusters"}} {cnt["req"]:.0f}')
                    out.append('http_server_requests_seconds_count{{method="GET",outcome="SUCCESS",status="200",uri="/actuator/prometheus"}} 99')
                    out.append(f'lettuce_seconds_sum{{command="GET",local="any",remote="redis:6379"}} {cnt["lsum"]:.6f}')
                    out.append(f'lettuce_seconds_count{{command="GET",local="any",remote="redis:6379"}} {cnt["lcnt"]:.0f}')
                    out.append('executor_queued_tasks{{name="applicationTaskExecutor"}} 0')
                    out.append(f"process_files_open_files {120 + int(40 * load)}")
                    out.append(f"process_cpu_usage {0.5 * load:.3f}")
                    out.append("process_start_time_seconds 1.7594e9")
                elif target == "postgres":
                    cnt["hit"] += 5 * 20000 * load
                    cnt["read"] += 5 * 50 * load
                    cnt["commit"] += 5 * 200 * load
                    cnt["chk"] += 0.02
                    cnt["brt"] += 5 * 3 * load
                    for db in ("rental", "postgres"):
                        f = 1.0 if db == "rental" else 0.01
                        out.append(f'pg_stat_database_blks_hit{{datid="1",datname="{db}"}} {cnt["hit"] * f:.0f}')
                        out.append(f'pg_stat_database_blks_read{{datid="1",datname="{db}"}} {cnt["read"] * f:.0f}')
                        out.append(f'pg_stat_database_xact_commit{{datid="1",datname="{db}"}} {cnt["commit"] * f:.0f}')
                        out.append(f'pg_stat_database_xact_rollback{{datid="1",datname="{db}"}} 3')
                        out.append(f'pg_stat_database_deadlocks{{datid="1",datname="{db}"}} 0')
                        out.append(f'pg_stat_database_temp_bytes{{datid="1",datname="{db}"}} 0')
                        out.append(f'pg_stat_database_temp_files{{datid="1",datname="{db}"}} 0')
                        out.append(f'pg_stat_database_conflicts{{datid="1",datname="{db}"}} 0')
                        out.append(f'pg_stat_database_blk_read_time{{datid="1",datname="{db}"}} {cnt["brt"] * f:.1f}')
                        out.append(f'pg_stat_database_numbackends{{datid="1",datname="{db}"}} {int(10 + 12 * load * f)}')
                    out.append(f"pg_stat_checkpointer_num_timed_total {cnt['chk']:.2f}")
                    for st, v in (("active", int(1 + 6 * load)), ("idle", 10), ("idle in transaction", 0)):
                        out.append(f'rental_pg_sessions_count{{state="{st}",wait_event_type=""}} {v}')
                    out.append("rental_pg_max_connections 48")
                    out.append(f'pg_stat_activity_max_tx_duration{{datname="rental",state="active"}} {0.05 + 0.2 * load:.3f}')
                    if node == "db-02":
                        out.append(f"pg_replication_lag_seconds {0.01 + 0.05 * load:.3f}")
                elif target == "redis":
                    cnt["hits"] += 5 * 100 * load
                    cnt["miss"] += 5 * 3 * load
                    out.append(f"redis_keyspace_hits_total {cnt['hits']:.0f}")
                    out.append(f"redis_keyspace_misses_total {cnt['miss']:.0f}")
                    out.append("redis_evicted_keys_total 0")
                    out.append(f"redis_memory_used_bytes {2e7 + 1e7 * load:.0f}")
                    out.append("redis_memory_max_bytes 1.34217728e8")
                    out.append(f"redis_connected_clients {int(10 + 10 * load)}")
                elif target == "nginx":
                    out.append(f"nginx_connections_active {int(5 + 40 * load)}")
                    out.append(f"nginx_http_requests_total {cnt['req']:.0f}")
            ts += 5
        # 긁기 하나가 gzip 멤버 하나 — 수집 스크립트와 같다. 실패한 긁기 하나를 섞는다
        chunks, cur = [], []
        for line in out:
            if line.startswith("# SCRAPE ") and cur:
                chunks.append("\n".join(cur) + "\n")
                cur = []
            cur.append(line)
        chunks.append("\n".join(cur) + "\n")
        # 실패한 긁기 — 머리 · 잘린 본문 · 오류 표시가 한 멤버(수집기와 같다). 본문의 값이 받아들여지면 안 된다
        bad_ts = float(chunks[3].split()[2]) + 1.0
        chunks.insert(3, f"# SCRAPE {bad_ts:.3f} {targets[-1]}\nnginx_connections_active 99999\nnode_load1 99\n# SCRAPE_ERROR {targets[-1]}\n")
        # 지금 수집기의 실패 — 본문 없는 한 줄. 앞 본문은 온전하다
        chunks.insert(6, f"# SCRAPE_ERROR {targets[0]}\n")
        return chunks

    for node, targets in (("app-01", ["node", "app-1", "app-2", "redis", "nginx"]), ("app-02", ["node", "app-1", "app-2"]),
                          ("db-01", ["node", "postgres"]), ("db-02", ["node", "postgres"])):
        with open(d / "metrics" / f"{node.replace('-', '')}.prom.gz", "wb") as f:
            for chunk in scrape_series(node, targets):
                f.write(gzip.compress(chunk.encode("utf-8")))

    # ---- DB 원자료
    for node, cpu in (("db-01", 0.6), ("db-02", 1.1)):
        lines = ["ts,wait_event_type,count"]
        for k in range(DURATION):
            ts = datetime.fromtimestamp(t0 + k, timezone.utc).isoformat().replace("+00:00", "Z")
            lines.append(f"{ts},CPU,{int(cpu * 2 * speed + (k % 3 == 0))}")
            if k % 4 == 0:
                lines.append(f"{ts},IO,1")
            if k % 10 == 0:
                lines.append(f"{ts},Client,2")
            if k % 50 == 0:
                lines.append(f"{ts},Lock,1")
            if k % 97 == 1:
                # 활성 세션이 없던 초
                lines[-1] = f"{ts},NONE,0"
        (d / "db" / f"{node.replace('-', '')}-wait.csv").write_text("\n".join(lines) + "\n", encoding="utf-8")
        rows = ["queryid,calls,total_exec_time_ms,mean_exec_time_ms,rows,shared_blks_hit,shared_blks_read,temp_blks_written,blk_read_time_ms,query"]
        qs = [("SELECT * FROM property WHERE id = $1", 4000, 1.2), ("SELECT grade FROM risk_analysis WHERE property_id = $1", 3500, 0.8),
              ("SELECT district, count(*) FROM property GROUP BY district", 900, 12.0), ("UPDATE users SET last_login = $1 WHERE id = $2", 300, 2.5)]
        for i, (q, calls, m) in enumerate(qs):
            calls = int(calls * (0.6 if node == "db-01" else 1.0))
            m *= speed
            rows.append(f'{1000 + i},{calls},{calls * m:.2f},{m:.3f},{calls},{calls * 8},{calls // 50},0,{calls * 0.01:.2f},"{q}"')
        (d / "db" / f"{node.replace('-', '')}-pgss.csv").write_text("\n".join(rows) + "\n", encoding="utf-8")
        # 전체 — collect.sh 와 같은 모양(datname + pg_stat_statements 전 열, gzip). 상위 20 밖의 앱 질의 · postgres DB 질의가 섞인다
        allrows = ["datname,userid,dbid,toplevel,queryid,query,plans,total_plan_time,calls,total_exec_time,mean_exec_time,rows,"
                   "shared_blks_hit,shared_blks_read,temp_blks_written,shared_blk_read_time"]
        for i, (q, calls, m) in enumerate(qs):
            calls = int(calls * (0.6 if node == "db-01" else 1.0))
            m *= speed
            allrows.append(f'rental,10,16384,t,{1000 + i},"{q}",0,0,{calls},{calls * m:.3f},{m:.3f},{calls},{calls * 8},{calls // 50},0,{calls * 0.01:.3f}')
        allrows.append('rental,10,16384,t,2001,"SELECT 1",0,0,5000,1000.000,0.200,5000,0,0,0,0')
        allrows.append('postgres,10,5,t,3001,"SELECT * FROM pg_stat_activity",0,0,20000,99999.000,5.000,20000,0,0,0,0')
        (d / "db" / f"{node.replace('-', '')}-pgss-all.csv.gz").write_bytes(gzip.compress(("\n".join(allrows) + "\n").encode("utf-8")))
        (d / "db" / f"{node.replace('-', '')}-dbstats.csv").write_text(
            "captured_utc,datname,numbackends,xact_commit,xact_rollback,blks_read,blks_hit,temp_bytes,deadlocks,stats_reset\n"
            f"{iso(end)},rental,20,50000,3,14000,3000000,0,0,2026-09-01 00:00:00+00\n"
            f"{iso(end)},postgres,1,100,0,5,500,0,0,2026-09-01 00:00:00+00\n", encoding="utf-8")
    (d / "db" / "db02-auto-explain.log").write_text(
        "2026-10-03 15:01:00.123 KST [4242] LOG:  duration: 25.123 ms  plan:\n\tQuery Text: SELECT * FROM property WHERE id = $1\n"
        "\tIndex Scan using property_pkey on property  (cost=0.43..8.45 rows=1 width=200)\n"
        "2026-10-03 15:02:00.456 KST [4243] LOG:  duration: 120.5 ms  plan:\n\tQuery Text: SELECT district, count(*) FROM property GROUP BY district\n"
        "\tHashAggregate\n\t  ->  Seq Scan on property  (cost=0.00..1.00 rows=5000000 width=8)\n"
        "2026-10-03 15:03:00.789 KST [4244] LOG:  duration: 30.0 ms  plan:\n\tQuery Text: SELECT ...\n\tNested Loop\n"
        "\t  ->  Index Only Scan using idx_a on a\n\t  ->  Bitmap Index Scan on idx_b\n", encoding="utf-8")

    # ---- 회차 전후
    pre_ts = (start - timedelta(seconds=30)).isoformat().replace("+00:00", "Z")
    post_ts = (end + timedelta(seconds=30)).isoformat().replace("+00:00", "Z")
    def cred(ts, ebs):
        return {"ts": ts, "nodes": {n: {"cpu_credit_balance": 280.0, "cpu_surplus_balance": 0.0, "cpu_surplus_charged": 0.0,
                                        "ebs_burst_balance": ebs if n.startswith("DB") else 100.0}
                                    for n in ("APP-01", "APP-02", "DB-01", "DB-02")}}
    (d / "pre" / "credits.json").write_text(json.dumps(cred(pre_ts, 99.0)), encoding="utf-8")
    (d / "post" / "credits.json").write_text(json.dumps(cred(post_ts, 97.5)), encoding="utf-8")
    def dbpre(primary):
        tables = {"property": {"n_live_tup": 5_000_000 if primary else 0, "n_dead_tup": 120, "reltuples": 5_000_000, "relpages": 90000,
                               "relallvisible": 89800, "vm_ratio": 0.998, "last_autovacuum": None},
                  "users": {"n_live_tup": 1_000_000 if primary else 0, "n_dead_tup": 10, "reltuples": 1_000_000, "relpages": 12000,
                            "relallvisible": 11900, "vm_ratio": 0.992, "last_autovacuum": None}}
        return {"captured_utc": pre_ts, "in_recovery": not primary, "db_size_bytes": 8.5e9, "index_size_bytes": 2.1e9,
                "blks_hit": 1_000_000, "blks_read": 10_000, "buffer_hit_ratio": 99.2, "stats_reset": "2026-09-01 00:00:00+00",
                "tables": tables, "autovacuum_running": [], "replication": [], "replay_lag_seconds": None if primary else 0.0}
    pre = {"round": name, "ts": pre_ts, "pgss_reset": {"db01": True, "db02": True},
           "db": {"db01": dbpre(True), "db02": dbpre(False)},
           "slots": {"app01": {"app-1": "healthy", "app-2": "healthy"}, "app02": {"app-1": "healthy", "app-2": "healthy"}},
           "batch_flags": {}, "warnings": []}
    (d / "pre" / "pre.json").write_text(json.dumps(pre, ensure_ascii=False), encoding="utf-8")
    (d / "pre" / "redis-commandstats.txt").write_text(
        f"# captured_utc {pre_ts}\n# Commandstats\r\ncmdstat_get:calls=100,usec=200,usec_per_call=2.00,rejected_calls=0,failed_calls=0\r\n"
        "cmdstat_set:calls=50,usec=150,usec_per_call=3.00,rejected_calls=0,failed_calls=0\r\n", encoding="utf-8")
    (d / "post" / "redis-commandstats.txt").write_text(
        f"# captured_utc {post_ts}\n# Commandstats\r\ncmdstat_get:calls=1100,usec=2200,usec_per_call=2.00,rejected_calls=0,failed_calls=0\r\n"
        "cmdstat_set:calls=250,usec=750,usec_per_call=3.00,rejected_calls=0,failed_calls=1\r\n"
        "cmdstat_publish:calls=10,usec=40,usec_per_call=4.00,rejected_calls=0,failed_calls=0\r\n"
        "cmdstat_info:calls=600,usec=6000,usec_per_call=10.00,rejected_calls=0,failed_calls=0\r\n"
        "cmdstat_config|get:calls=60,usec=300,usec_per_call=5.00,rejected_calls=0,failed_calls=0\r\n", encoding="utf-8")
    (d / "profiler" / "app01-app-1-cpu.collapsed").write_text(
        "java/lang/Thread.run;com/duri/rentalplatform/domain/risk/service/RiskQueryService.find;com/duri/rentalplatform/domain/risk/calculator/RiskCalculator.calc 400\n"
        "java/lang/Thread.run;com/duri/rentalplatform/domain/auth/service/AuthService.login;org/springframework/security/crypto/bcrypt/BCrypt.crypt_raw 300\n"
        "java/lang/Thread.run;tools/jackson/databind/ObjectMapper.writeValue 100\n"
        "[C2 CompilerThread0];C2Compiler::compile_method 200\n", encoding="utf-8")
    vm = ["procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu----- -----timestamp-----",
          " r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st                 UTC",
          # 첫 줄은 부팅 뒤 평균 — 버려야 한다(99% 는 걸리면 드러난다)
          f" 1  0      0 500000  10000 200000    0    0     0     5  900 1500 98  1  1  0  0 {datetime.fromtimestamp(t0 + lead + 1, timezone.utc):%Y-%m-%d %H:%M:%S}"]
    for k in range(0, DURATION, 5):
        ts = datetime.fromtimestamp(t0 + k, timezone.utc)
        idle = 80 - int(30 * k / DURATION)
        vm.append(f" 1  0      0 500000  10000 200000    0    0     0     5  900 1500 {100 - idle - 3}  3 {idle}  0  0 {ts:%Y-%m-%d %H:%M:%S}")
    (d / "gen" / "vmstat.txt").write_text("\n".join(vm) + "\n", encoding="utf-8")
    return d, nreq


def main():
    base = datetime(2026, 10, 3, 6, 0, 0, tzinfo=timezone.utc)
    make_round("_fixture_R01", base, speed=1.0, changed="", seed=1)
    make_round("_fixture_R02", base + timedelta(hours=1), speed=0.8, changed="risk 판정 기준 표 캐시", seed=2)
    # 기록이 JMeter 보다 60초 먼저 시작해 30초 늦게 끝난 회차 — 통계 구간이 JMeter 기준인지 본다
    make_round("_fixture_W01", base + timedelta(hours=2), speed=1.0, changed="", seed=3, lead=60, trail=30)
    print("가짜 회차를 만들었다:", RESULTS_DIR / "_fixture_R01", RESULTS_DIR / "_fixture_R02", RESULTS_DIR / "_fixture_W01")


if __name__ == "__main__":
    main()
