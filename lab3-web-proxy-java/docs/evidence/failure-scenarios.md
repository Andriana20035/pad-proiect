# Scenarii de eșec – laboratorul 3

## Scenariul 1: DW A devine indisponibil

- **Stare inițială:** storage, DW A, DW B și proxy funcționează; `healthy=true` pentru ambele DW-uri.
- **Injectarea eșecului:** `POST http://127.0.0.1:9011/admin/fault?mode=fail` sau oprirea procesului DW A.
- **Comportament observat:** health check-ul marchează A indisponibil; un GET nou prin proxy poate primi `200 OK` de la DW B.
- **Recuperare:** `mode=ok` sau repornirea procesului A. Health check-ul îl reintroduce în rotație.
- **Stare finală:** ambele DW-uri disponibile și cu aceeași versiune de date din storage.
- **Garanție:** un singur DW căzut nu oprește operațiile de citire/scriere, cât timp celălalt și storage răspund.
- **Limită:** un GET început înainte de detectarea defecțiunii poate necesita retry.

## Scenariul 2: Un DW devine lent, apoi ambele sunt suprasolicitate

- **Stare inițială:** ambele DW-uri în modul `ok`.
- **Injectarea eșecului:** `mode=slow&delayMs=900` (timeout proxy 700 ms), plus cereri concurente.
- **Comportament observat:** retry GET, creșterea p95/p99, eventual răspunsuri 429 / 504.
- **Recuperare:** readucem ambele DW-uri în `ok` și permitem circuit breaker-ului să revină în `CLOSED`.
- **Stare finală:** proxy-ul continuă să preia cereri, iar backends pot răspunde din nou.
- **Garanție:** numărul de cereri API active este limitat la 12; nu promitem succes în orice suprasarcină.
- **Limită:** timpul total poate depăși 700 ms când există o a doua încercare.

## Scenariul 3: Oprirea reală a procesului DW A

`py scripts/process_failure.py` pornește procese Java distincte, oprește DW A, execută GET/PUT prin DW B, repornește DW A și verifică faptul că vede datele actualizate. Apoi oprește și repornește storage, verificând persistența informației.

- **Stare inițială:** toate cele patru procese sunt pornite.
- **Injectarea eșecului:** terminarea procesului Java DW A.
- **Comportament observat:** DW B servește cererile, iar scrierile sale sunt păstrate de serviciul comun.
- **Recuperare:** repornirea DW A și verificarea noii valori; repornirea storage după oprirea lui.
- **Stare finală:** datele confirmate anterior pot fi citite și după restart.
- **Garanție:** recuperare după căderea unui DW și persistență la restart normal al stocării.
- **Limită:** storage este un punct unic de eșec. Dacă storage este oprit, ambele DW-uri pierd accesul la date; nu există replicare a serviciului de stocare.

## Dovezi automate

- `failure-run.json` – verificarea HTTP, fault injection și percentile.
- `process-failure-run.json` – căderea unui proces Java, recuperare și persistență.
- `benchmark.md` și `benchmark.json` – p50/p95/p99 pentru 80 de cereri pe scenariu.

Rezultatele se regenerează pe calculatorul pe care se prezintă lucrarea. Capturile de ecran nu înlocuiesc rularea testelor sau logurile.
