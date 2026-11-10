# Structura proiectului

| Folder / fișier | Rol |
|---|---|
| `src/main/java/md/lab3/Main.java` | Pornirea individuală a proceselor |
| `ProxyServer.java` | Cache, distribuire, health check, retry, metrici |
| `BackendServer.java` | Endpoint DW, verificare stare, injecție de erori |
| `StorageServer.java` și `WarehouseStore.java` | Sincronizarea DW-urilor și persistența |
| `TtlCache.java`, `CircuitBreaker.java`, `LatencyMetrics.java` | Mecanisme auxiliare |
| `src/test/` | Teste Java |
| `scripts/` | Rulare și verificări automate |
| `contracts/` | Contracte HTTP, JSON și XML |
| `docs/diagrams/` | Diagrame |
| `docs/adr/` | Decizie arhitecturală |
| `docs/evidence/` | Rezultate ale testelor |
