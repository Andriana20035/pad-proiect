# ADR-001 - gRPC + Protocol Buffers + SQLite pentru comunicarea inter-servicii

## Context
Lab 2 cere comunicare RPC intre servicii, contract explicit, tratarea erorilor de retea si
timeout-urilor, persistenta, rutare si load balancing. Solutia trebuie sa ramana usor de
rulat local in IntelliJ si usor de demonstrat la evaluare.

## Decizie
Folosim gRPC Java peste HTTP/2, cu Protocol Buffers ca IDL/format. Brokerul expune metode
unary pentru operatii scurte si `Subscribe` server-streaming pentru livrari de lunga durata.
Starea brokerului este persistata in SQLite WAL. Fiecare mesaj are o livrare separata pentru
fiecare abonat activ. O livrare este finalizata numai la `ACK PERSISTED`.

Retry-ul este controlat de aplicatie: o livrare in-flight are lease; la expirare sau NACK
retryable revine in PENDING cu backoff exponential. Dupa 3 incercari intra in DLQ. Consumerul
persista local efectul si messageId in aceeasi tranzactie pentru deduplicare dupa restart.

Clientii primesc o lista de endpoint-uri. Apelurile unary folosesc round-robin; un stream
Subscribe ramane pe nodul curent si, la cadere, se reconecteaza la urmatorul endpoint.

## Alternative
- Socket TCP + JSON: mai simplu, dar repeta Lab 1 si cere protocol manual.
- REST/HTTP JSON: usor de inspectat, dar nu demonstreaza RPC tipizat si streaming gRPC.
- RabbitMQ/Kafka: excelente pentru messaging, dar tema este RPC, iar infrastructura ar
  ascunde mecanismul cerut in acest increment.
- Baza externa PostgreSQL: mai potrivita pentru productie/multi-node, dar mareste inutil
  dependentele pentru laborator.

## Consecinte
Contractul este tipizat si code-generated; HTTP/2 multiplexeaza RPC-urile; streaming-ul
reduce polling-ul clientului. SQLite ofera persistenta si backup simplu. Retry, DLQ si
idempotenta sunt vizibile si testabile.

## Limita cunoscuta
SQLite partajata intre doua noduri este o simplificare didactica, nu o arhitectura HA de
productie. Un stream deja deschis nu este reechilibrat intre noduri. Nu promitem exactly-once
pentru efecte externe; garantia practica este at-least-once cu deduplicare persistenta si
retry limitat.
