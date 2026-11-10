#!/usr/bin/env python3
"""Automated HTTP acceptance and failure proof. Requires storage, two DW processes and proxy on the default localhost ports."""
import argparse
import concurrent.futures
import datetime as dt
import json
import os
import platform
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

ROOT = os.environ.get("LAB3_BASE_URL", "http://127.0.0.1:18080")
BACKENDS = ("http://127.0.0.1:9011", "http://127.0.0.1:9012")


def send(url, method="GET", accept=None, timeout=6):
    headers = {"Accept": accept} if accept else {}
    req = urllib.request.Request(url, method=method, data=b"" if method in ("POST", "PUT") else None, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, response.read().decode(), {k.lower(): v for k, v in response.headers.items()}
    except urllib.error.HTTPError as err:
        return err.code, err.read().decode(), {k.lower(): v for k, v in err.headers.items()}


def fault(backend, mode, delay=0):
    code, body, _ = send(f"{backend}/admin/fault?mode={mode}&delayMs={delay}", "POST")
    assert code == 200, (backend, mode, code, body)


def stats():
    code, body, _ = send(ROOT + "/metrics")
    assert code == 200
    return json.loads(body)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="docs/evidence/failure-run.json")
    args = parser.parse_args()
    nonce = str(time.time_ns())
    evidence = {
        "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "environment": {"python": platform.python_version(), "os": platform.system(), "arch": platform.machine()},
        "proxy": ROOT,
        "scenarios": {},
    }
    for backend in BACKENDS:
        fault(backend, "ok")
    assert send(ROOT + "/health")[0] == 200
    evidence["initial_metrics"] = stats()
    try:
        url = ROOT + f"/api/orders/ORD-2001?verify-cache={nonce}"
        first = send(url); second = send(url)
        assert first[0] == second[0] == 200
        assert first[2].get("x-cache") == "MISS" and second[2].get("x-cache") == "HIT"
        assert send(ROOT + "/api/orders/ORD-2001", accept="application/xml")[1].startswith("<order>")
        assert send(ROOT + "/api/stats", accept="application/xml")[1].startswith("<stats>")
        put = send(ROOT + "/api/orders/ORD-2001?status=READY", "PUT")
        assert put[0] == 200, put
        after_put = send(url)
        assert after_put[0] == 200 and after_put[2].get("x-cache") == "MISS" and "READY" in after_put[1]
        # Distinct uncached keys must exercise both DW nodes via proxy Round-Robin.
        for i in range(10):
            assert send(ROOT + f"/api/orders/ORD-2003?verify-roundrobin={nonce}-{i}")[0] == 200
        distribution = stats()["backends"]
        assert distribution[0]["selected"] > 0 and distribution[1]["selected"] > 0, distribution
        evidence["scenarios"]["round_robin"] = {
            "selected_a": distribution[0]["selected"],
            "selected_b": distribution[1]["selected"], "result": "PASS",
        }
        evidence["scenarios"]["cache_and_contract"] = {
            "first": first[2].get("x-cache"), "second": second[2].get("x-cache"),
            "after_put": after_put[2].get("x-cache"), "put_status": put[0],
            "xml": "validated order and stats", "result": "PASS",
        }

        # Single backend fault: peer keeps uncached GET successful, breaker opens after failures.
        fault(BACKENDS[0], "fail")
        statuses = [send(ROOT + f"/api/orders/ORD-2001?verify-failover={nonce}-{i}")[0] for i in range(16)]
        after_failure = stats()
        assert all(code == 200 for code in statuses), statuses
        # Health checks may exclude the DW before its circuit breaker reaches OPEN.
        assert not after_failure["backends"][0]["healthy"] or after_failure["backends"][0]["circuit"] == "OPEN", after_failure
        evidence["scenarios"]["single_backend_unavailable"] = {
            "injection": "DW A mode=fail -> HTTP 503", "requests": len(statuses),
            "statuses": dict(Counter(map(str, statuses))),
            "breaker_A": after_failure["backends"][0]["circuit"],
            "healthy_A": after_failure["backends"][0]["healthy"],
            "retries_total": after_failure["retries"], "result": "PASS",
        }

        # Recovery: allow cooldown and send distinct GETs to exercise HALF_OPEN probe.
        fault(BACKENDS[0], "ok")
        time.sleep(1.7)
        for i in range(16):
            assert send(ROOT + f"/api/orders/ORD-2001?verify-recovery={nonce}-{i}")[0] == 200
        recovered = stats()
        assert recovered["backends"][0]["healthy"] and recovered["backends"][0]["circuit"] == "CLOSED", recovered
        evidence["scenarios"]["recovery"] = {
            "action": "DW A mode=ok; cooldown > 1.5s; GET half-open probe",
            "breaker_A": recovered["backends"][0]["circuit"], "result": "PASS",
        }

        # Same persistent store is visible via both independently running DW nodes.
        a_read = send(BACKENDS[0] + "/api/orders/ORD-2001")
        b_read = send(BACKENDS[1] + "/api/orders/ORD-2001")
        assert a_read[0] == b_read[0] == 200 and a_read[1] == b_read[1], (a_read,b_read)
        corr = "lab3-check-" + nonce
        request = urllib.request.Request(ROOT + "/api/orders/ORD-2003?corr="+nonce,
                headers={"X-Correlation-Id":corr})
        with urllib.request.urlopen(request,timeout=6) as resp:
            assert resp.headers.get("X-Correlation-Id") == corr
        evidence["scenarios"]["synchronization_and_correlation"] = {
            "same_response_on_both_dw": True, "correlation":corr, "result":"PASS"
        }

        # Stale-if-error: after TTL, cached record can be returned briefly if both nodes fail.
        stale_url = ROOT + f"/api/orders/ORD-2002?verify-stale={nonce}"
        assert send(stale_url)[0] == 200
        time.sleep(2.15)
        fault(BACKENDS[0], "fail")
        fault(BACKENDS[1], "fail")
        stale = send(stale_url)
        assert stale[0] == 200 and stale[2].get("x-cache") == "STALE", stale
        assert stale[2].get("x-degraded") == "stale-if-error", stale
        evidence["scenarios"]["both_unavailable_stale"] = {
            "injection": "DW A, DW B mode=fail; wait > cache TTL 2000 ms",
            "http_status": stale[0], "x_cache": stale[2].get("x-cache"),
            "x_degraded": stale[2].get("x-degraded"), "result": "PASS",
        }

        # Load shedding: >=42 concurrent uncached requests to both slow nodes; max in-flight=12.
        fault(BACKENDS[0], "ok")
        fault(BACKENDS[1], "ok")
        time.sleep(1.7)
        fault(BACKENDS[0], "slow", 900)
        fault(BACKENDS[1], "slow", 900)
        with concurrent.futures.ThreadPoolExecutor(max_workers=42) as pool:
            statuses = list(pool.map(lambda i: send(ROOT + f"/api/orders/ORD-2003?verify-overload={nonce}-{i}")[0], range(72)))
        assert 429 in statuses, statuses
        final = stats()
        assert final["rejected429"] > 0, final
        assert 0 <= final["p50Ms"] <= final["p95Ms"] <= final["p99Ms"]
        evidence["scenarios"]["both_slow_load_shed"] = {
            "injection": "DW A, DW B mode=slow 900 ms; 72 requests, 42 threads",
            "http_status_counts": dict(Counter(map(str, statuses))),
            "proxy_max_in_flight": 12, "rejected429_total": final["rejected429"],
            "observed_p50_ms": final["p50Ms"], "observed_p95_ms": final["p95Ms"],
            "observed_p99_ms": final["p99Ms"], "result": "PASS",
        }
        evidence["final_metrics"] = final
        evidence["result"] = "PASS"
        print("PASS: cache, XML/JSON, PUT, Round-Robin, health checks, synchronized DW, correlation ID, stale, 429, percentiles")
        print("HTTP statuses under overload:", dict(Counter(statuses)))
        print("/metrics p50/p95/p99:", final["p50Ms"], final["p95Ms"], final["p99Ms"])
    finally:
        for backend in BACKENDS:
            fault(backend, "ok")
    target = Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n", encoding="utf8")
    print("Saved:", target)


if __name__ == "__main__":
    main()
