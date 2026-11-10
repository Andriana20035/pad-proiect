# Laboratorul 3 – ce am realizat și de ce

## Scopul lucrării

Am realizat un sistem HTTP distribuit în care clientul trimite cererile către un **reverse proxy**, nu direct către serverele Data Warehouse. Scopul a fost să reducem timpul de răspuns pentru citirile repetate și să controlăm comportamentul sistemului când unul dintre servere răspunde lent sau nu mai funcționează.

Am implementat aplicația în Java 17. Pentru testare am folosit JUnit 5, scripturi Python și Maven.

## Arhitectura

Sistemul este format din patru procese Java:

1. **Reverse proxy (`18080`)** – primește cererile de la client, gestionează cache-ul, alege un DW și aplică protecțiile.
2. **DW A (`9011`)** – server HTTP care oferă date în JSON sau XML.
3. **DW B (`9012`)** – al doilea server HTTP, care poate prelua cereri atunci când A nu este disponibil.
4. **Serviciul comun de stocare (`9020`)** – primește operațiile DW-urilor, serializează scrierile și păstrează comenzile în fișierul `data/warehouse.properties`.

DW A și DW B nu folosesc aceeași instanță Java de `WarehouseStore`. Ele rulează în procese separate, iar datele sunt consistente pentru că se citesc și se modifică în același serviciu de stocare. Aceasta este varianta noastră de **serviciu de sincronizare**, în locul unei baze Cassandra. Dacă se oprește un DW, celălalt poate continua să folosească datele. Dacă se oprește serviciul comun de stocare, ambele DW-uri pierd accesul la date; aceasta este o limitare importantă.

## Ce se întâmplă la o cerere GET

1. Clientul accesează, de exemplu, `http://127.0.0.1:18080/api/orders/ORD-2001`.
2. Proxy-ul verifică în cache dacă are un răspuns valabil pentru același URL și formatul solicitat.
3. Dacă răspunsul există și nu a expirat, îl returnează direct (**HIT**).
4. Dacă nu există, alege un DW disponibil prin **Round-Robin** (**MISS**).
5. DW-ul întreabă serviciul de stocare și întoarce datele în JSON sau XML.
6. Proxy-ul trimite răspunsul clientului și îl poate memora temporar în cache.

**TTL** este perioada cât un răspuns rămâne valabil în cache; am ales 2 secunde pentru a-l putea demonstra ușor.

## Ce se întâmplă la o cerere PUT

Pentru o cerere precum `PUT /api/orders/ORD-2001?status=READY`, proxy-ul trimite modificarea către un DW disponibil. Acesta transmite operația către serviciul comun de stocare, care actualizează datele persistente. După operație, informația actualizată devine vizibilă prin ambele DW-uri.

Proxy-ul invalidează cache-ul pentru comenzile și statisticile afectate. Am introdus și un **contor de generație**: dacă o scriere se produce în timpul unei citiri, proxy-ul nu memorează rezultatul acelei citiri ca fiind nou. Nu păstrăm niciun lacăt global în timpul apelurilor HTTP către DW-uri; blocările scurte se folosesc numai la actualizarea cache-ului și a generației.

## Load balancing

Am implementat distribuirea **Round-Robin**: pentru cererile fără cache, proxy-ul alternează nodurile eligibile. Dacă un DW nu este sănătos sau circuitul său este deschis, este ocolit. Distribuirea poate fi observată în `/metrics`, prin contorii `selected` ai fiecărui DW.

## Protecția când un DW este lent sau indisponibil

- **Health check:** proxy-ul verifică aproximativ la fiecare 500 ms ruta `/health` a fiecărui DW și exclude din rotație nodurile indisponibile. Când nodul revine, poate fi ales din nou.
- **Timeout:** proxy-ul așteaptă maximum 700 ms pentru fiecare încercare către un DW.
- **Retry și backoff/jitter:** când un GET eșuează, proxy-ul poate încerca celălalt DW, după o întârziere mică și variabilă.
- **Circuit breaker:** după eșecuri repetate, un DW este evitat temporar. Mai târziu se permite o încercare de verificare (HALF_OPEN).
- **Limitarea concurenței:** proxy-ul admite maximum 12 cereri API active simultan. Cererile suplimentare primesc `429 Too Many Requests`.
- **Stale-if-error:** dacă DW-urile nu răspund, proxy-ul poate returna pentru o perioadă scurtă o valoare expirată din cache, marcată `X-Cache: STALE`.

## Smart-proxy și identificarea cererilor

În soluția noastră, **smart-proxy** înseamnă că proxy-ul păstrează legătura dintre fiecare cerere client activă și răspunsul trimis înapoi clientului, reutilizează conexiunile HTTP către DW-uri și păstrează pentru cererile active identificatorul, adresa clientului și calea accesată. Registrul activ poate fi consultat prin câmpul `activeClients` din `/metrics`. Nu implementăm autentificare, sesiuni persistente sau un registru distribuit în Redis.

Dacă clientul trimite `X-Correlation-Id`, proxy-ul îl păstrează; altfel generează unul. Acest identificator este transmis DW-ului și apare în logurile sale. Astfel putem urmări aceeași cerere prin mai multe componente.

## Testarea și măsurătorile

- `mvn clean test` execută testele JUnit; `mvn package` construiește aplicația.
- `scripts/verify.py` verifică funcționalități HTTP, indisponibilitatea unui DW, recuperarea și protecția la suprasarcină.
- `scripts/benchmark.py` generează cereri în mai multe scenarii și calculează **p50**, **p95** și **p99**.

p50 este timpul sub care se află aproximativ jumătate dintre cereri. p95 și p99 descriu întârzierile pe care le întâmpină cele mai lente 5%, respectiv 1% dintre cereri. Testele măsoară răspunsurile văzute de client, inclusiv răspunsurile de eroare rapide; de aceea trebuie privite împreună cu numărul de cereri `200`, `429` și `504`.

## Cum explicăm scenariul de eșec

**Starea inițială:** storage, DW A, DW B și proxy sunt pornite; ambele DW-uri sunt sănătoase.

**Injectarea eșecului:** setăm DW A în modul `fail` sau `slow` folosind `/admin/fault`.

**Comportamentul observat:** proxy-ul poate utiliza DW B și evită nodul indisponibil. În `/metrics` observăm starea `healthy`, retry-urile, circuit breaker-ul și timpii de răspuns.

**Recuperare:** readucem DW A în modul `ok`; health check-ul îl reintroduce în rotație. Datele rămân disponibile deoarece stocarea este comună.

**Garanții:** un DW poate cădea fără a pierde imediat serviciul de citire/scriere, dacă celălalt DW și storage sunt disponibile. Cache-ul limitează apelurile repetate, iar cererile în exces sunt respinse controlat.

**Limite:** serviciul de stocare este un punct unic de eșec; soluția demonstrativă nu oferă replicare geografică, autentificare, tranzacții distribuite sau garanții de latență end-to-end. Datele sunt persistate într-un fișier, dar nu există garanție de recuperare după coruperea discului sau pierderea alimentării. Simularea erorilor este disponibilă local și nu trebuie expusă public.

## Unde este implementat fiecare lucru

| Funcție | Fișier |
|---|---|
| Pornirea modurilor storage/backend/proxy | `src/main/java/md/lab3/Main.java` |
| Reverse proxy, Round-Robin, cache, health check, retry, breaker | `src/main/java/md/lab3/ProxyServer.java` |
| Serverul DW, fault injection, loguri cu correlation ID | `src/main/java/md/lab3/BackendServer.java` |
| Serviciul de sincronizare și persistență | `src/main/java/md/lab3/StorageServer.java`, `WarehouseStore.java` |
| Cache și TTL | `src/main/java/md/lab3/TtlCache.java` |
| Circuit breaker | `src/main/java/md/lab3/CircuitBreaker.java` |
| Percentile | `src/main/java/md/lab3/LatencyMetrics.java` |
| Teste | `src/test/`, `scripts/verify.py`, `scripts/benchmark.py` |

Pentru prezentare, este suficient să explicăm fluxul cererii și să demonstrăm mecanismele prin comenzile din `GHID_DEMONSTRATIE.md`.
