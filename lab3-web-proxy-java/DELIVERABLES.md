# Livrabile – Laboratorul 3

| Nr. | Livrabil | Unde se găsește |
|---:|---|---|
| 1 | Repository | https://github.com/Andriana20035/pad-proiect |
| 2 | Tag pentru predare | `lab3-v1.0` (se creează după finalizarea și încărcarea versiunii) |
| 3 | Cod sursă | `src/main/java/md/lab3/` |
| 4 | Contracte | `contracts/openapi.yaml`, `contracts/order-v1.schema.json`, `contracts/order-v1.xsd` |
| 5 | Teste automate | `src/test/java/md/lab3/`, `scripts/verify.py`, `scripts/benchmark.py`, `scripts/process_failure.py` |
| 6 | Diagrame | `docs/diagrams/architecture.md`, `docs/diagrams/failure-sequence.md` |
| 7 | ADR | `docs/adr/ADR-001-java-http-proxy-cache-resilience.md` |
| 8 | Dovezi de eșec | `docs/evidence/failure-run.json`, `docs/evidence/process-failure-run.json`, `docs/evidence/benchmark.md` |
| 9 | Pași de reproducere | `docs/GHID_DEMONSTRATIE.md` |
| 10 | Garanții și limite | `docs/CE_AM_IMPLEMENTAT.md`, `README.md` |

## Verificare

```powershell
mvn clean test
mvn package
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 start
py .\scripts\verify.py
py .\scripts\benchmark.py --requests 80
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 stop
py .\scripts\process_failure.py
```

După teste se adaugă folderul `lab3-web-proxy-java` în repository, fără `target/`, `data/`, `.class`, `.jar` sau fișiere secrete. Instrucțiunile Git sunt în `docs/GHID_GITHUB.md`.
