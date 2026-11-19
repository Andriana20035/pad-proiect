# ADR-001 – Reverse proxy și stocare sincronizată prin serviciu comun

### Context

Laboratorul presupune implementarea unui reverse proxy cu caching, load balancing și mecanisme de reziliență. Nodurile DW trebuie să funcționeze independent, iar datele să rămână consistente după oprirea și repornirea unuia dintre ele. Proxy-ul trebuie să gestioneze eficient și cererile simultane.

### Decizie

Am utilizat Java 17, `HttpServer` și `HttpClient`. Proxy-ul, cele două DW-uri și serviciul comun de stocare rulează în procese separate. Serviciul comun serializează scrierile și păstrează datele într-un fișier persistent.

Proxy-ul implementează Round-Robin, cache cu TTL și invalidare prin generații, health check periodic, timeout, retry GET cu backoff/jitter și circuit breaker.

În versiunea v1.1 am modificat gestionarea concurenței:

- Limita de 12 operații se aplică accesului către DW, fără a bloca răspunsurile din cache.
- Smart-proxy-ul identifică cererile GET identice aflate simultan în execuție și reutilizează rezultatul unei singure solicitări către DW.
- Distribuția Round-Robin este verificată separat, în condiții normale, folosind 100 de cereri distincte.

### Alternative

- **Cassandra / SGBD distribuit:** oferă posibilități de scalare și replicare, dar necesită o infrastructură mai complexă.
- **Redis:** poate păstra cache-ul și starea sesiunilor, însă pentru reunirea cererilor locale am utilizat `ConcurrentHashMap` și `CompletableFuture`.
- **Replicare directă DW–DW:** ar necesita mecanisme suplimentare de sincronizare și reconciliere după eșecuri.

### Consecințe

DW-urile pot fi oprite independent. Un nod indisponibil este exclus temporar din rotație, iar celălalt poate continua procesarea cererilor. Datele confirmate de serviciul comun rămân disponibile după repornire.

Cache-ul reduce numărul apelurilor către DW, iar Smart-proxy-ul evită solicitările simultane duplicate. Limitarea concurenței protejează backend-urile fără a respinge inutil citirile din cache.

### Limită cunoscută

Serviciul comun de stocare rămâne un punct unic de eșec. Registrul Smart-proxy este local și nu păstrează sesiuni persistente. Nu sunt implementate replicarea stocării, autentificarea sau tranzacțiile distribuite. Soluția este destinată demonstrării mecanismelor într-un mediu local.