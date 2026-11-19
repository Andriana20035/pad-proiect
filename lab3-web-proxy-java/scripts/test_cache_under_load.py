
import json
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request, urlopen
from urllib.error import HTTPError

BASE = "http://127.0.0.1:18080"
NODES = ["http://127.0.0.1:9011", "http://127.0.0.1:9012"]

def get(url):
    req = Request(url, headers={"Accept": "application/json"})
    try:
        with urlopen(req, timeout=8) as response:
            return response.status, response.headers.get("X-Cache")
    except HTTPError as error:
        return error.code, error.headers.get("X-Cache")

def fault(node, mode, delay=0):
    url = f"{node}/admin/fault?mode={mode}&delayMs={delay}"
    req = Request(url, data=b"", method="POST")
    with urlopen(req, timeout=5):
        pass

def metrics():
    with urlopen(BASE + "/metrics", timeout=3) as response:
        return json.load(response)

test_id = str(uuid.uuid4())
cached_url = BASE + "/api/orders/ORD-2001?cache-test=" + test_id

start = threading.Event()

def slow_request(i):
    start.wait()
    url = BASE + f"/api/orders/ORD-2002?load={test_id}-{i}"
    return get(url)

try:
    # Mai intai memoram raspunsul in cache
    status, cache = get(cached_url)
    assert status == 200, "Nu s-a putut incarca raspunsul in cache"

    # Facem ambele DW-uri lente
    for node in NODES:
        fault(node, "slow", 1500)

    with ThreadPoolExecutor(max_workers=20) as pool:
        futures = [pool.submit(slow_request, i) for i in range(20)]
        start.set()

        # Asteptam sa fie ocupate cele 12 locuri
        deadline = time.monotonic() + 1.0
        maximum = 0

        while time.monotonic() < deadline:
            current = metrics()["inflight"]
            maximum = max(maximum, current)
            if maximum >= 12:
                break
            time.sleep(0.01)

        # Testam cache-ul cat timp DW-urile sunt ocupate
        hit_status, hit_cache = get(cached_url)

        for future in futures:
            future.result()

    print("Maximum operatii DW simultane:", maximum)
    print("Status citire cache:", hit_status)
    print("X-Cache:", hit_cache)

    if maximum == 12 and hit_status == 200 and hit_cache == "HIT":
        print("PASS - Cache-ul functioneaza sub suprasarcina")
    else:
        print("FAIL - Verifica limita sau expirarea TTL-ului")

finally:
    for node in NODES:
        fault(node, "ok")
