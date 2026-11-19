
import json
import uuid
from urllib.request import urlopen

BASE = "http://127.0.0.1:18080"

def metrics():
    with urlopen(BASE + "/metrics", timeout=5) as response:
        return json.load(response)

before = metrics()

assert len(before["backends"]) == 2, "Sunt necesare doua DW-uri"

for node in before["backends"]:
    assert node["healthy"] and node["circuit"] == "CLOSED", \
        "Ambele DW-uri trebuie sa fie disponibile"

test_id = str(uuid.uuid4())

for i in range(100):
    url = BASE + f"/api/orders/ORD-2001?rr-test={test_id}-{i}"
    with urlopen(url, timeout=5) as response:
        assert response.status == 200

after = metrics()

dw_a = after["backends"][0]["selected"] - before["backends"][0]["selected"]
dw_b = after["backends"][1]["selected"] - before["backends"][1]["selected"]

print("DW A:", dw_a, "cereri")
print("DW B:", dw_b, "cereri")

assert dw_a == 50 and dw_b == 50, \
    "Distributia Round-Robin nu este 50/50"

print("PASS - Round-Robin distribuie egal cererile")
