# Scenariu de eșec – DW A indisponibil

```mermaid
sequenceDiagram
    participant C as Client
    participant P as Proxy
    participant A as DW A
    participant B as DW B
    participant S as Storage comun
    P->>A: GET /health periodic
    A--xP: Eroare / indisponibil
    Note over P: healthy(A)=false; A exclus din selecție
    C->>P: GET /api/orders/ORD-2001
    P->>B: GET /api/orders/ORD-2001
    B->>S: Citire date
    S-->>B: Date comune
    B-->>P: 200 JSON
    P-->>C: 200 JSON
    Note over A: Procesul DW A este repornit
    P->>A: GET /health
    A-->>P: 200 OK
    Note over P: healthy(A)=true; A reintrat în rotație
```

Un GET deja aflat în tranzit poate încă eșua sau poate necesita retry înainte să fie actualizată starea din health check. Când ambele DW-uri sunt indisponibile, se poate folosi cache-ul stale pentru un interval limitat sau răspunsul 503/504.
