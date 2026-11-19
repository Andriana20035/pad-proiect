
import concurrent.futures
import json
import threading
import urllib.request
import uuid

BASE = "http://127.0.0.1:18080"
DW = ["http://127.0.0.1:9011", "http://127.0.0.1:9012"]

def fault(node, mode, delay=0):
    url = f"{node}/admin/fault?mode={mode}&delayMs={delay}"
    req = urllib.request.Request(url, data=b"", method="POST")
    with urllib.request.urlopen(req, timeout=5):
        pass

def metrics():
    with urllib.request.urlopen(BASE + "/metrics") as response:
        return json.load(response)

barrier = threading.Barrier(20)
url = BASE + "/api/orders/ORD-2002?smart-test=" + str(uuid.uuid4())

def client_request(i):
    barrier.wait()
    with urllib.request.urlopen(url, timeout=6) as response:
        return response.status, response.headers.get("X-Smart-Proxy")

try:
    for node in DW:
        fault(node, "slow", 400)

    before = metrics()

    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(client_request, range(20)))

    after = metrics()

    calls = sum(
        after["backends"][i]["selected"] - before["backends"][i]["selected"]
        for i in range(2)
    )

    joined = sum(header == "COALESCED" for _, header in results)

    print("Clienti HTTP:", len(results))
    print("Apeluri catre DW:", calls)
    print("Cereri reunite:", joined)
    print("Coduri HTTP:", [status for status, _ in results])

    if calls == 1 and joined > 0 and all(s == 200 for s, _ in results):
        print("PASS - Smart-proxy functioneaza corect")
    else:
        print("FAIL - Smart-proxy necesita verificare")

finally:
    for node in DW:
        fault(node, "ok")
