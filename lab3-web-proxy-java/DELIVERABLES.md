
| Nr. | Livrabil | Unde se găsește |
|---:|---|---|
| 1 | Repository | https://github.com/Andriana20035/pad-proiect |
| 2 | Tag pentru predare | `lab3-v1.1` |
| 3 | Cod sursă | `src/main/java/md/lab3/` |
| 4 | Contracte | `contracts/openapi.yaml`, `contracts/order-v1.schema.json`, `contracts/order-v1.xsd` |
| 5 | Teste automate | `src/test/java/md/lab3/`, `scripts/verify.py`, `scripts/process_failure.py`, `scripts/test_cache_under_load.py`, `scripts/test_smart_proxy.py`, `scripts/test_round_robin.py` |
| 6 | Diagrame | `docs/diagrams/architecture.md`, `docs/diagrams/failure-sequence.md` |
| 7 | ADR | `docs/adr/ADR-001-java-http-proxy-cache-resilience.md` |
| 8 | Dovezi de eșec | `docs/evidence/failure-run.json`, `docs/evidence/process-failure-run.json`, `docs/evidence/benchmark.md` |
| 9 | Pași de reproducere | `docs/GHID_DEMONSTRATIE.md` |
| 10 | Garanții și limite | `docs/CE_AM_IMPLEMENTAT.md`, `README.md` |



## Verificare

Compilarea și testele Java:

```powershell
mvn clean test
mvn package
```

Pornirea aplicației:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 start
```

Verificarea funcționalităților:

```powershell
py .\scripts\test_round_robin.py
py .\scripts\test_smart_proxy.py
py .\scripts\test_cache_under_load.py
py .\scripts\verify.py
py .\scripts\benchmark.py --requests 80
```

Oprirea aplicației:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 stop
```

Verificarea opririi independente a unui DW:

```powershell
py .\scripts\process_failure.py
```

Comenzile se execută din directorul `lab3-web-proxy-java`. Testul Round-Robin necesită două DW-uri sănătoase, cu circuitele în starea CLOSED.
 Instrucțiunile Git sunt în `docs/GHID_GITHUB.md`.




## Corecturi v1.1

În urma observațiilor primite la laborator au fost efectuate trei corecturi:

- Limita de 12 operații concurente se aplică accesului către DW, fără a bloca citirile din cache.
- Smart-proxy-ul reunește cererile GET identice primite simultan, utilizând un singur apel către DW.
- Distribuția Round-Robin este verificată separat prin 100 de cereri distincte.

### Rezultatele testelor

| Test | Rezultat |
|---|---|
| Cache sub suprasarcină | 12 operații DW active; 200 OK / HIT |
| Smart-proxy | 20 clienți, 1 apel către DW, 19 cereri reunite |
| Round-Robin | DW A: 50 cereri, DW B: 50 cereri |
| Verificare generală | PASS |
| Teste Java | BUILD SUCCESS |

Corecturile sunt realizate pe ramura `fix/lab3-v1.1`. După integrarea în `main`, versiunea va fi marcată cu tagul `lab3-v1.1`. Tagul `lab3-v1.0` rămâne neschimbat.