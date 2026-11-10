# Laboratorul 3 – Web Proxy, cache și reziliență

## Scop

Implementarea unui reverse proxy HTTP care distribuie cererile între două servere Data Warehouse, păstrează temporar răspunsuri GET și controlează erorile cauzate de noduri lente sau indisponibile.

## Componente

```text
Client HTTP
    |
    v
Reverse proxy :18080  (cache, Round-Robin, retry, health checks, circuit breaker, metrici)
    |                            |
    v                            v
DW A :9011                    DW B :9012
    |                            |
    +-------------+--------------+
                  v
      Storage / sync :9020
       data/warehouse.properties
```

Fiecare componentă se pornește ca **proces Java separat**. DW A și DW B au propria instanță de server HTTP; datele lor sunt sincronizate prin serviciul comun de stocare. Acesta serializează actualizările și păstrează starea pe disc. Soluția nu folosește Cassandra sau Redis.

## Funcții

- `GET /api/orders`, `GET /api/orders/{id}`, `GET /api/stats` – JSON sau XML (`Accept: application/xml`).
- `PUT /api/orders/{id}?status=READY` – actualizează starea unei comenzi.
- Cache GET cu TTL de 2 secunde, invalidare după PUT, `stale-if-error`.
- Round-Robin doar între DW-uri sănătoase; verificare `/health` la circa 500 ms.
- Timeout 700 ms/încercare, retry GET cu backoff și jitter, circuit breaker per DW.
- Maximum 12 cereri API simultane; respingere cu HTTP 429 la suprasarcină.
- Propagarea `X-Correlation-Id`, loguri DW și registru pentru cererile client active.
- Metrici `/metrics`: număr cereri, HIT/MISS, retry, 429, starea DW-urilor și p50/p95/p99.

## Pornire rapidă – Windows / PowerShell

Necesită **JDK 17+, Maven și Python 3**. În VS Code deschide acest folder și rulează:

```powershell
mvn clean test
mvn package
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 start
```

În browser:

- http://127.0.0.1:18080/api/orders/ORD-2001
- http://127.0.0.1:18080/metrics

În terminal:

```powershell
py .\scripts\verify.py
py .\scripts\benchmark.py --requests 80
```

Pentru oprirea celor patru procese:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 stop
```

Dacă `py` nu este disponibil, încearcă `python`. Înainte de benchmark, este bine să repornești procesele pentru a goli cache-ul și a reseta metricile.

**Instrucțiuni complete pentru prezentare:** [docs/GHID_DEMONSTRATIE.md](docs/GHID_DEMONSTRATIE.md).

**Explicația laboratorului:** [docs/CE_AM_IMPLEMENTAT.md](docs/CE_AM_IMPLEMENTAT.md).

**Adăugarea în GitHub și taguri:** [docs/GHID_GITHUB.md](docs/GHID_GITHUB.md).

## Testul cu procese Java separate

Pentru a verifica oprirea reală a unui DW, recuperarea și persistența, mai întâi oprește aplicația pornită cu `start`, apoi:

```powershell
py .\scripts\process_failure.py
```

Testul pornește patru procese, oprește DW A, verifică failover către DW B și scrierea în timpul avariei, repornește DW A, apoi repornește storage. Rezultatul este în `docs/evidence/process-failure-run.json`.

## Contracte și livrabile

- `src/main/java/md/lab3/` – implementarea Java;
- `src/test/java/md/lab3/` – teste JUnit 5;
- `contracts/` – OpenAPI, schema JSON și schema XML;
- `docs/diagrams/` – diagramele arhitecturii și degradării;
- `docs/adr/` – decizia arhitecturală;
- `docs/evidence/` – dovezile testelor de avarie și benchmark;
- `DELIVERABLES.md` – lista celor 10 livrabile obligatorii.

## Limite

Serviciul de stocare este un punct unic de eșec. Nu există autentificare sau replicarea stocării. Fișierul de date poate fi pierdut/corupt în caz de defect al discului. Cache-ul poate furniza temporar date expirate în regim de avarie, iar timpul total al unui GET poate depăși 700 ms când există retry. Scripturile `/admin/fault` se folosesc doar local.
