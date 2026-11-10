# Arhitectura laboratorului 3

```mermaid
flowchart TD
    C[Client HTTP] --> P[Reverse Proxy :18080]
    P --> K[(Cache TTL + generații)]
    P --> L[Round-Robin / health checks / retry]
    L --> A[DW A :9011 - JVM separată]
    L --> B[DW B :9012 - JVM separată]
    A --> S[Serviciu comun de sincronizare :9020]
    B --> S
    S --> D[(Fișier persistent data/warehouse.properties)]
    P --> M[/Metrici p50 / p95 / p99/]
```

DW A și DW B rulează în procese separate și folosesc același serviciu de stocare. Un DW căzut poate reveni fără sincronizare manuală. Serviciul de stocare rămâne un punct unic de eșec.
