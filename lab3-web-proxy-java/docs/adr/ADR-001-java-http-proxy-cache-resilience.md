# ADR-001 – Reverse proxy și stocare sincronizată prin serviciu comun

**Data:** 10.11.2026  
**Status:** Acceptat

### Context

Laboratorul cere un reverse proxy cu cache și distribuirea cererilor către mai multe DW-uri, plus mecanisme de reziliență. Nodurile DW trebuie să poată fi oprite separat, iar datele trebuie să rămână consistente după repornirea unuia dintre ele.

### Decizie

Am ales Java 17 cu `HttpServer` și `HttpClient`. Rulăm proxy-ul, două DW-uri și un serviciu de sincronizare/stocare în procese separate. Serviciul de stocare serializează scrierile și păstrează comenzile într-un fișier persistent. Proxy-ul folosește Round-Robin, cache TTL, invalidare cu generații, health check periodic, timeout, retry GET cu backoff/jitter, circuit breaker și un semafor de concurență.

### Alternative

- **Cassandra / alt SGBD distribuit:** potrivit pentru scalare și disponibilitate, dar presupune administrarea unui cluster pentru acest exercițiu.
- **Redis:** util pentru cache sau stare de sesiune, dar nu este necesar pentru registrul local de cereri active.
- **Replicare directă DW–DW:** mai dificil de reconciliat după o cădere parțială.

### Consecințe

Un DW poate fi oprit și repornit fără pierderea ultimei actualizări confirmate de serviciul comun. Health check-ul exclude nodurile indisponibile, iar proxy-ul poate redirecționa cererile către celălalt DW. Cache-ul reduce încărcarea la citiri repetate.

### Limită cunoscută

Serviciul de stocare este un singur punct de eșec. Nu oferim replicarea stocării, autentificare, tranzacții distribuite sau garanție la coruperea fizică a datelor. Este o arhitectură demonstrativă locală.
