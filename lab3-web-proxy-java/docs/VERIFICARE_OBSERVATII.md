# Verificarea punctelor discutate la laborator

| Observație | Rezolvare în proiect | Verificare |
|---|---|---|
| DW-uri separate | `Main.java` pornește modurile `backend` în procese Java distincte | `scripts/process_failure.py` termină numai DW A; DW B rămâne activ |
| Stocare comună și persistentă | `StorageServer.java` și `WarehouseStore.java`, date salvate în `data/warehouse.properties` | PUT vizibil prin ambele DW-uri; restart DW și storage |
| Blocare globală la scriere | `ProxyServer.java` nu ține niciun lacăt în timpul cererilor HTTP; generație + invalidare cache | Inspecția metodei `put`, `get` și testele de invalidare |
| Health check | `probeBackends()` verifică periodic `/health` și marchează `healthy` | Oprire DW A și câmpul `backends[].healthy` în `/metrics` |
| Smart-proxy | `activeClients` păstrează informații despre cererile active; HttpClient reutilizează conexiunile către DW | `/metrics` → `activeClients`; codul `ProxyServer.java` |
| X-Correlation-Id | Antet păstrat sau generat de proxy, transmis către DW și afișat în log | `process_failure.py`, `target/process-failure-logs/` |

**Limită importantă:** serviciul de stocare central este un punct unic de eșec. Soluția nu utilizează Cassandra sau Redis și nu este un sistem distribuit pregătit pentru producție. ADR-ul explică alegerea.
