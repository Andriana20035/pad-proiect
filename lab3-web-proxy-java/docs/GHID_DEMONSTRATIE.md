# Laboratorul 3 – pașii pentru demonstrație

Acesta este ghidul de urmat la prezentarea laboratorului în VS Code, pe Windows. În această variantă se pornesc **patru procese Java separate**: serviciul de stocare, DW A, DW B și reverse proxy. Nu trebuie pornite laboratoarele 1 sau 2.

## 1. Deschiderea proiectului

1. Deschide **VS Code**.
2. Alege **File → Open Folder** și selectează `lab3-web-proxy-java` (folderul cu `pom.xml`).
3. Din meniul **Terminal → New Terminal** deschide un terminal PowerShell.
4. Verifică dacă terminalul este în `...\lab3-web-proxy-java`. Dacă nu, intră în folder folosind `cd`.

Verifică aplicațiile necesare:

```powershell
java -version
mvn -version
py --version
```

Sunt necesare Java 17+, Maven și Python 3. Dacă `py` nu există, încearcă `python --version`.

## 2. Testele Java și compilarea

În terminal, din folderul laboratorului:

```powershell
mvn clean test
mvn package
```

La sfârșitul fiecărei comenzi caută `BUILD SUCCESS`. `mvn package` creează `target/lab3-web-proxy-1.0.0.jar`.

## 3. Pornirea celor patru procese

În același terminal:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 start
```

Scriptul pornește **separat**:

- storage – port `9020`, baza de date simplă persistentă din `data/warehouse.properties`;
- DW A – port `9011`;
- DW B – port `9012`;
- reverse proxy – port `18080`.

La final apar PID-urile proceselor și căile către logurile din `target/`.

Pentru a vedea procesele Java individuale:

```powershell
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -like '*lab3-web-proxy*' } | Select-Object ProcessId, CommandLine
```

## 4. Ce deschizi în Chrome sau Edge

Introdu pe rând următoarele adrese în bara de navigare (nu în Google Search):

| Adresă | Ce demonstrează |
|---|---|
| http://127.0.0.1:18080/health | Proxy-ul răspunde |
| http://127.0.0.1:18080/api/orders/ORD-2001 | Datele unei comenzi prin proxy, în JSON |
| http://127.0.0.1:18080/api/orders | Lista comenzilor |
| http://127.0.0.1:18080/metrics | Cache, distribuire, health check, circuit breaker și percentile |
| http://127.0.0.1:9011/health | DW A răspunde |
| http://127.0.0.1:9012/health | DW B răspunde |
| http://127.0.0.1:9020/health | Serviciul comun de stocare răspunde |

Statusul unei comenzi poate fi diferit de `CREATED` dacă ai executat deja PUT: datele se păstrează pe disc între reporniri.

## 5. Cache MISS, HIT, TTL și XML

În terminal rulează rapid, de două ori, **aceeași comandă**:

```powershell
curl.exe -i "http://127.0.0.1:18080/api/orders/ORD-2001?demo-cache=1"
curl.exe -i "http://127.0.0.1:18080/api/orders/ORD-2001?demo-cache=1"
```

Prima cerere arată `X-Cache: MISS`, a doua arată `X-Cache: HIT`, dacă a fost executată în mai puțin de 2 secunde. Dacă a trecut mai mult timp, repetă folosind `?demo-cache=2` la ambele cereri. TTL-ul implicit este de 2 secunde.

Pentru XML:

```powershell
curl.exe -i -H "Accept: application/xml" "http://127.0.0.1:18080/api/orders/ORD-2002"
```

Vezi un răspuns cu `<order>`, `<id>` și `<status>`.

## 6. PUT și stocarea comună

Schimbă statusul unei comenzi prin proxy:

```powershell
curl.exe -i -X PUT "http://127.0.0.1:18080/api/orders/ORD-2001?status=READY"
```

Apoi verifică aceeași comandă prin fiecare DW:

```powershell
curl.exe "http://127.0.0.1:9011/api/orders/ORD-2001"
curl.exe "http://127.0.0.1:9012/api/orders/ORD-2001"
```

**Ambele răspunsuri** trebuie să conțină statusul `READY`. Datele nu sunt copiate manual între DW-uri: ambele folosesc serviciul de stocare de pe portul 9020.

## 7. Un DW indisponibil și revenirea lui

Simulează indisponibilitatea lui DW A:

```powershell
curl.exe -X POST "http://127.0.0.1:9011/admin/fault?mode=fail"
Start-Sleep -Seconds 1
```

Deschide în browser `http://127.0.0.1:18080/metrics`: la DW A trebuie să apară `"healthy":false`. Apoi execută:

```powershell
curl.exe -i "http://127.0.0.1:18080/api/orders/ORD-2002?demo-failover=1"
```

În continuare trebuie să primești `200 OK`, cu `X-Backend: dw-b`. Restabilește DW A:

```powershell
curl.exe -X POST "http://127.0.0.1:9011/admin/fault?mode=ok"
Start-Sleep -Seconds 1
```

Reîncarcă `/metrics`: `healthy` revine la `true`. Health check-ul rulează la fiecare aproximativ 500 ms. Pentru că proxy-ul exclude un nod și pe baza health check-ului, nu este obligatoriu ca circuit breaker-ul să ajungă în starea OPEN în această demonstrație.

## 8. X-Correlation-Id și logurile DW

```powershell
curl.exe -i -H "X-Correlation-Id: demo-profesoara-123" "http://127.0.0.1:18080/api/orders/ORD-2003?demo-correlation=1"
Select-String -Path .\target\dw-*.out.log -Pattern "demo-profesoara-123"
```

Identificatorul primit de la client este păstrat de proxy, transmis DW-ului și apare în logul DW-ului care a procesat cererea. Folosește un parametru nou în adresă pentru a evita un răspuns deja memorat în cache.

## 9. Testele automate de reziliență și benchmark

**Înainte de test**, toate DW-urile trebuie să fie în modul `ok` (comenzile de la pasul 7 le restabilesc).

```powershell
py .\scripts\verify.py
```

La final trebuie să apară `PASS`. Dovezile sunt salvate în `docs/evidence/failure-run.json`.

Pentru un benchmark mai curat, oprește și repornește serviciile, apoi rulează:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 stop
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 start
py .\scripts\benchmark.py --requests 80
```

Scriptul produce `docs/evidence/benchmark.md` și `docs/evidence/benchmark.json`. Compară p50, p95 și p99 între MISS, HIT, DW lent și DW indisponibil. Valorile depind de calculator.

Dacă pe calculator comanda `py` nu există, înlocuiește-o cu `python`.

## 9b. Test suplimentar: oprirea REALĂ a unui proces DW

După ce ai demonstrat benchmark-ul, oprește serviciile pornite prin script și execută testul separat:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 stop
py .\scripts\process_failure.py
```

Acesta pornește automat patru procese Java, oprește DW A, arată că DW B continuă să citească și să scrie, repornește DW A și verifică aceleași date. Oprește și repornește apoi serviciul de stocare pentru a verifica persistența. Produce `docs/evidence/process-failure-run.json`, iar logurile din `target/process-failure-logs/` păstrează `correlationId`. La final, procesele lansate de test sunt oprite automat.

## 10. Oprirea laboratorului

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 stop
```

**Ce merită prezentat profesoarei:** schema din `docs/diagrams/architecture.md`, cele patru procese, cache MISS/HIT, PUT vizibil pe ambele DW-uri, DW A indisponibil cu failover, correlation ID în log, raportul benchmark și ADR-ul.

### Probleme frecvente

- `Address already in use`: există procese vechi pe porturile 18080/9011/9012/9020; oprește-le înainte de a reporni.
- `mvn` nerecunoscut: Maven lipsește din PATH.
- `py` nerecunoscut: încearcă `python`.
- `X-Cache: MISS` la a doua cerere: a expirat TTL-ul de 2 secunde sau URL-ul/antetul Accept diferă.
- Datele nu revin la `CREATED` după restart: comportament normal, deoarece sunt persistente. Dacă vrei datele inițiale, oprește procesele și fă o copie de siguranță înainte să ștergi `data/warehouse.properties`.
- `503` pentru toate cererile: verifică dacă serviciul de stocare `:9020` funcționează.
