# Maparea structurii recomandate la Maven / IntelliJ

Structura conceptuală din cerință este păstrată astfel:

| Structură recomandată | În proiectul Java |
|---|---|
| `src/api/` | `src/main/java/md/lab/api/` |
| `src/order-service/` | `src/main/java/md/lab/orderservice/` |
| `src/consumers/` | `src/main/java/md/lab/consumers/` |
| `src/integration-service/` | `src/main/java/md/lab/integrationservice/` |
| `tests/unit/` | `src/test/java/md/lab/unit/` |
| `tests/integration/` | `src/test/java/md/lab/integration/` |
| `tests/contract/` | `src/test/java/md/lab/contract/` |
| `tests/failure/` | `src/test/java/md/lab/failure/` |

Această mapare folosește layout-ul standard Maven, astfel încât IntelliJ recunoaște automat source roots și test roots.
