#!/usr/bin/env python3
"""Reproducible HTTP p50/p95/p99 load / resilience test. Python 3 stdlib only.

Run when storage, both DW processes and proxy are serving ports 9020/9011/9012/18080.
Use --output docs/evidence/benchmark.md to generate measured evidence, not invented values.
"""
import argparse
import concurrent.futures
import json
import os
import math
import time
import platform
import datetime as dt
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

ROOT = os.environ.get("LAB3_BASE_URL", "http://127.0.0.1:18080")
A = "http://127.0.0.1:9011"
B = "http://127.0.0.1:9012"


def call(url, method="GET", timeout=6):
    start = time.perf_counter()
    req = urllib.request.Request(url, data=b"" if method == "POST" else None, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, (time.perf_counter() - start) * 1000, {k.lower(): v for k,v in response.headers.items()}
    except urllib.error.HTTPError as exc:
        return exc.code, (time.perf_counter() - start) * 1000, {k.lower(): v for k,v in exc.headers.items()}
    except (urllib.error.URLError, TimeoutError) as exc:
        return 0, (time.perf_counter() - start) * 1000, {"error": str(exc)}


def fault(backend, mode, delay=0):
    code, _, _ = call(f"{backend}/admin/fault?mode={mode}&delayMs={delay}", "POST")
    if code != 200:
        raise RuntimeError(f"Cannot set {backend} mode={mode}: HTTP {code}")


def percentile(values, q):
    values = sorted(values)
    return values[max(0, math.ceil(q * len(values)) - 1)] if values else 0


def recover_circuits():
    # A previous failure scenario can leave a breaker OPEN after the fault was removed.
    # Wait past its cooldown, then send uncached probes to both healthy instances.
    time.sleep(1.7)
    for i in range(12):
        call(ROOT + f"/api/orders/ORD-2001?recovery={time.time_ns()}-{i}")


def bench(name, n, concurrency, path_fn):
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = list(pool.map(lambda i: call(ROOT + path_fn(i)), range(n)))
    latencies = [row[1] for row in results]
    statuses = Counter(row[0] for row in results)
    cache = Counter(row[2].get("x-cache", "-") for row in results)
    return {"scenario": name, "n": n, "concurrency": concurrency,
            "p50_ms": round(percentile(latencies, .50), 2),
            "p95_ms": round(percentile(latencies, .95), 2),
            "p99_ms": round(percentile(latencies, .99), 2),
            "statuses": dict(sorted(statuses.items())), "cache": dict(cache)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="docs/evidence/benchmark.md")
    parser.add_argument("--requests", type=int, default=90)
    args = parser.parse_args()
    code, _, _ = call(ROOT + "/health")
    if code != 200:
        raise SystemExit("Start the Java servers first; /health is not responding")
    rows = []
    try:
        fault(A,"ok"); fault(B,"ok")
        recover_circuits()
        normal = bench("normal / cache MISS",args.requests,4,
                       lambda i: f"/api/orders/ORD-2001?normal={time.time_ns()}-{i}")
        if normal["statuses"].get(200, 0) < int(args.requests * .95):
            raise RuntimeError(f"Normal baseline did not pass 95% successful responses: {normal}")
        rows.append(normal)
        cache_url = f"/api/orders/ORD-2002?cached={time.time_ns()}"
        warm = call(ROOT + cache_url)  # warm a single exact cache key
        validation = call(ROOT + cache_url)
        if warm[0] != 200 or validation[0] != 200 or validation[2].get("x-cache") != "HIT":
            raise RuntimeError(f"Failed to warm/verify cache HIT. Warm={warm}, validation={validation}")
        hits = bench("normal / cache HIT",args.requests,4,lambda i: cache_url)
        if hits["cache"].get("HIT", 0) < int(args.requests * .95):
            raise RuntimeError(f"Cache HIT benchmark was not actually HIT: {hits}")
        rows.append(hits)
        fault(A,"slow",800)
        rows.append(bench("one backend slow",args.requests,8,
                          lambda i: f"/api/orders/ORD-2001?slow={time.time_ns()}-{i}"))
        fault(A,"ok"); fault(A,"fail")
        rows.append(bench("one backend returns 503",args.requests,8,
                          lambda i: f"/api/orders/ORD-2001?fail={time.time_ns()}-{i}"))
        fault(A,"ok"); fault(B,"ok")
        recover_circuits()
        fault(A,"slow",800); fault(B,"slow",800)
        rows.append(bench("both slow / load shedding",args.requests,36,
                          lambda i: f"/api/orders/ORD-2001?overload={time.time_ns()}-{i}"))
    finally:
        fault(A,"ok"); fault(B,"ok")
    header = ("| Scenario | Requests | Concurrency | p50 (ms) | p95 (ms) | p99 (ms) | HTTP status | Cache |\n"
              "|---|---:|---:|---:|---:|---:|---|---|\n")
    lines = ["# PAD Lab 3 – measured HTTP performance", "",
             "Generated by `scripts/benchmark.py`. Percentiles use nearest-rank over client-observed latency.",
             "Environment: local HTTP proxy with two Data Warehouse servers.", "",header.rstrip("\n")]
    for row in rows:
        lines.append(f"| {row['scenario']} | {row['n']} | {row['concurrency']} | {row['p50_ms']} | {row['p95_ms']} | {row['p99_ms']} | {row['statuses']} | {row['cache']} |")
    lines += ["", "## Interpretation", "",
              "- A cache HIT bypasses both warehouses and protects them during repeated reads.",
              "- A single slow/unavailable node triggers timeout/503 and a retry with backoff+jitter to the second node.",
              "- Repeated failures trigger the circuit breaker; periodic /health checks also exclude unreachable nodes.",
              "- With both nodes slow, the proxy responds with bounded 429/503/504 errors instead of piling up unbounded work.",
              "- p50/p95/p99 are measurements of this local run and vary with CPU, OS and scheduling; no SLA is implied.", ""]
    target = Path(args.output)
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text("\n".join(lines),encoding="utf-8")
    target.with_suffix(".json").write_text(json.dumps({
        "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "environment": {"python": platform.python_version(), "os": platform.system(), "arch": platform.machine()},
        "notes": "Măsurători locale ale latenței HTTP; nu reprezintă un SLA",
        "results": rows,
    },indent=2),encoding="utf-8")
    print("\n".join(lines[:10])); print(f"Saved: {target} and {target.with_suffix('.json')}")


if __name__ == "__main__":
    main()
