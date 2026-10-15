# Laborator 2 PAD - Comunicarea inter-servicii prin RPC/gRPC

Proiect Java/Maven care implementeaza comunicarea dintre servicii prin **gRPC**, cu
**Protocol Buffers** si transport **HTTP/2**. Structura pastreaza stilul laboratorului 1,
dar inlocuieste protocolul TCP/JSON personalizat cu un contract RPC `.proto` si stub-uri
generate.

## Task-uri

- contract RPC versionat in `src/main/proto/messaging_rpc.proto` si `contracts/`;
- apeluri **unary**: CreateTopic, Publish, Unsubscribe, Ack, Nack, GetStats, ListDlq, Health;
- apel **server-streaming**: Subscribe;
- mesaje cu `id`, `timestamp`, `correlation_id`, `schema_version`, `type`, `key`, `payload`;
- persistenta SQLite in mod WAL;
- livrare separata per abonat;
- ACK pe trei stadii: `RECEIVED`, `PROCESSED`, `PERSISTED`;
- numai `PERSISTED` finalizeaza livrarea;
- retry limitat la 3 incercari si backoff exponential;
- DLQ persistenta;
- deduplicare persistenta la consumer;
- crash dupa efectul local, inainte de ACK-ul final;
- reconnect automat si failover la urmatorul endpoint;
- load balancing client-side round-robin pentru apelurile unary;
- doua instante broker pot folosi aceeasi baza SQLite pentru demonstratia locala;
- topicuri dinamice, subscribe/unsubscribe si validarea abonamentului la ACK;
- teste unit, contract, integration si failure;
- Subscribe asincron: stream-urile lungi ruleaza pe un executor separat si nu blocheaza RPC-urile unary;
- notificare pentru livrari noi, cu fallback rar pentru schimbari scrise de alt nod in SQLite.

## De ce gRPC

Contractul `.proto` descrie functiile si tipurile de date, iar Maven genereaza clasele
client/server. Producerul nu mai serializeaza manual un protocol de comenzi ca in Lab 1.
Pentru Lab 2, comunicarea este la nivel RPC: clientul apeleaza `publish()`, `ack()` etc.,
ca si cum ar apela metode locale, iar gRPC gestioneaza codificarea protobuf si HTTP/2.


DemoMain porneste doua noduri gRPC cu aceeasi stocare persistenta, doi consumatori si
un producer. Inventory simuleaza o deconectare dupa efectul local persistent, dar inainte
de `ACK PERSISTED`; mesajul este relivrat dupa lease + backoff, iar deduplicarea pastreaza
efectul local la 1.

Rezultat asteptat:

```text
Inventory ORD-2001 effectCount = 1
Inventory ORD-2002 effectCount = 1
Completed deliveries = 4
DLQ size = 0
```

## Build si verificare

JDK 22 este compatibil; Maven compileaza cu `release 17`. Din radacina proiectului:

```powershell
mvn clean test
mvn "-Dtest=ManySubscribersDoNotStarveUnaryRpcTest" test
mvn "-Dtest=ConsumerFailoverTest" test
.\scripts\verify-deliverables.ps1
mvn package
java -jar target/lab2-rpc-grpc-1.0.0-all.jar
```

JAR-ul `-all.jar` include dependentele si porneste `DemoMain` prin manifest.
Pentru IDE, `build-helper-maven-plugin` inregistreaza cele doua directoare generate din
protobuf; dupa import, foloseste Maven Reload daca indexarea nu s-a actualizat.
Nu copia si nu edita clasele generate. Vezi [runbook](docs/runbook/README.md).

## Rulare in procese separate in IntelliJ

1. Ruleaza `md.lab2.server.BrokerMain` cu VM options:
   `-Dlab2.port=50051 -Dlab2.node=broker-a -Dlab2.db=data/lab2-broker.db`
2. Porneste a doua instanta `BrokerMain` cu:
   `-Dlab2.port=50052 -Dlab2.node=broker-b -Dlab2.db=data/lab2-broker.db`
3. Ruleaza `md.lab2.demo.TopicAdminMain` pentru a crea topicul `orders.v1`.
4. Ruleaza `md.lab2.demo.InventoryConsumerMain`.
5. Ruleaza `md.lab2.demo.AuditConsumerMain`.
6. Ruleaza `md.lab2.demo.OrderProducerMain` cu un orderId nou, de exemplu `ORD-2002`.

Pentru scenariul cerut de esec, Inventory se poate porni cu:

```text
-Dlab2.hardCrashOnce=true
```

Prima procesare va salva efectul local si va opri procesul cu exit code 17 inainte de
ACK-ul `PERSISTED`. Porneste din nou Inventory fara aceasta optiune: acelasi mesaj este
redat, `DEDUP_SKIP` apare in log, apoi se trimite ACK final.

## Despre deadline

Apelurile unary au deadline implicit de 2 secunde. `Subscribe` este un stream de lunga
durata si **nu primeste deadline scurt**. Pe server, metoda `Subscribe` inregistreaza
abonamentul si returneaza imediat firul gRPC; bucla de livrare ruleaza pe un executor
separat, elastic. Cand `Publish`, `ACK PERSISTED`, `NACK` sau `Unsubscribe` modifica
starea, workerii sunt notificati fara `Thread.sleep()`. Exista doar un fallback configurabil
(`lab2.crossNodeRefreshMs`, implicit 1000 ms) pentru a observa schimbari scrise de un alt
proces broker in aceeasi baza SQLite. Daca brokerul cade, stream-ul se inchide, iar
ConsumerClient se reconecteaza cu backoff si selecteaza urmatorul endpoint.

## Load balancing

ProducerClient foloseste round-robin intre endpoint-urile din `LAB2_ENDPOINTS` / 
`-Dlab2.endpoints`. Astfel apelurile unary sunt distribuite intre broker-a si broker-b.
Un stream `Subscribe` ramane pe nodul pe care a fost deschis; la deconectare, consumerul
alege urmatorul endpoint. Aceasta limita si executorul separat pentru Subscribe sunt
documentate in [ADR-001](docs/adr/ADR-001-grpc-protobuf-sqlite.md).

Doua apeluri din aceeasi instanta `ProducerClient` rotesc endpoint-urile. Fiecare proces nou
`OrderProducerMain` creeaza un client nou si reseteaza cursorul; comenzile independente nu
constituie o demonstratie de round-robin. `DemoMain` si `LoadBalancingConcurrencyTest`
folosesc acelasi client pentru a demonstra alternarea. Failover-ul este separat: dupa
`UNAVAILABLE`/`DEADLINE_EXCEEDED`, se incearca urmatorul endpoint, cu numar limitat de apeluri.
Ordinea endpoint-urilor stabileste primul nod incercat; un port gresit nu redirectioneaza
automat serverul. Porturile clientului trebuie sa corespunda brokerilor porniti.

## Persistenta si retry

Baza brokerului retine:

- topicuri;
- abonamente;
- mesaje acceptate;
- livrari per consumer;
- starea ACK;
- lease pentru livrarile in curs;
- momentul urmatorului retry;
- DLQ.

La livrare, `attempt` creste. Daca ACK-ul final nu vine pana la expirarea lease-ului,
livrarea este reprogramata cu backoff. Dupa a treia incercare nereusita intra in DLQ.
Un NACK permanent (`retryable=false`) merge direct in DLQ.

Consumerii au baze SQLite locale separate pentru efect si `messageId` procesat. Efectul
si deduplicarea sunt scrise in aceeasi tranzactie, astfel incat restartul consumerului nu
pierde informatia de idempotenta.

## Garantii si limite

- `PublishReply.accepted=true` inseamna commit la broker, nu efect executat de consumatori.
  Logul `PUBLISH_CONFIRMED` confirma acelasi lucru; `COMPLETED` necesita ACK `PERSISTED`.
- `RECEIVED`: consumerul a primit livrarea; `PROCESSED`: handlerul s-a terminat;
  `PERSISTED`: consumerul confirma rezultatul durabil. In handlerul demonstrativ,
  efectul local si deduplicarea sunt deja persistate inainte de `PROCESSED`.
- Livrarea este at-least-once cu retry limitat: se reincearca dupa pierderea ACK-ului;
  dupa numarul configurat de esecuri, mesajul ramane in DLQ. Nu garantam procesarea
  cu succes a fiecarui mesaj si nici retry nelimitat. At-most-once ar elimina relivrarea,
  dar ar putea pierde efectul daca un consumer cade; nu este modul implementat aici.
- Deduplicarea locala dupa `messageId`, in aceeasi tranzactie cu efectul SQLite, previne
  repetarea acelui efect. Nu garantam exactly-once pentru un API sau o plata externa.
  Producerul reutilizeaza acelasi Envelope la retry. Pentru `orderCreated`, ID-ul este
  stabil per orderId; repetarea acelui ID cu alt payload/topic este respinsa.
  Timestamp-ul unei retransmisii nu schimba identitatea logica; primul timestamp ramane salvat.
- Ordering: exista cel mult o livrare IN_FLIGHT per `(consumerId, topic)`. Dintre
  livrarile PENDING deja scadente, se alege cel mai mic deliveryId. Un mesaj in backoff
  poate fi depasit de unul nou: nu garantam FIFO strict peste retry, ordine globala intre
  topicuri/brokeri sau ordine pe `key`. Mai multe stream-uri cu acelasi consumerId
  reprezinta acelasi abonat logic si pot primi incercari succesive ale aceleiasi livrari.
- Abonamentul este durabil: deconectarea/`close()` nu este `Unsubscribe`. Mesajele noi
  sunt rutate numai catre abonamente active la momentul Publish; nu exista replay
  retroactiv pentru un consumer nou. Unsubscribe opreste numai perechea consumer/topic;
  livrarile vechi sunt pastrate pentru o eventuala reabonare.
  Recuperarea lease-urilor expirate se face la urmatorul claim al consumerului/topicului;
  daca nu exista stream conectat, nu ruleaza un scheduler global de recuperare.
- Subscribe este asincron fata de firul RPC, dar nu complet non-blocking: fiecare stream
  foloseste un worker elastic care poate astepta. Nu exista o limita fixa de 8 pe broker,
  dar nici admission control pentru un numar nelimitat de stream-uri.
- SQLite WAL are un singur writer logic. La milioane de mesaje, dimensiunea DB, fsync,
  indexurile, contention si scanarile pot creste latenta; nu exista benchmark de un milion
  de mesaje sau politica de retentie implementata. Pentru productie: partitionare/sharding,
  broker distribuit dedicat sau baza server potrivita. Nu sunt implementate aici.
- Cei doi brokeri impart acelasi fisier pe aceeasi masina/volum local; nu exista replicare
  intre masini si rezistenta la pierderea discului comun. Nu folosi WAL pe un network share.
- Transportul demonstrativ este HTTP/2 plaintext local, fara TLS/autentificare.
  Verificarea owner-ului ACK/NACK foloseste consumerId primit, nu o identitate autentificata.
  `Health` verifica disponibilitatea RPC, nu face o proba de scriere pe disc.
- Shutdown-ul asteapta gRPC si ambii executors pana la circa 3 secunde; la depasire
  raporteaza timeout in log. O operatie SQLite blocata nu poate fi garantat oprita instantaneu.

## DLQ, backup si restore

`Nack(retryable=false)` trimite imediat in DLQ; NACK retryable sau lease expirat aplica
backoff exponential, cu maximum 3 incercari implicit. `ProducerClient.listDlq()` si
`stats()` permit inspectia; nu este implementata o operatie RPC de redrive din DLQ.

```powershell
.\scripts\run-lab2.ps1 -Mode stats
.\scripts\run-lab2.ps1 -Mode backup
```

Backup-ul foloseste `VACUUM INTO`, nu copia naiv doar fisierul `.db` cat timp WAL este activ.
Destinatia trebuie sa difere de baza sursa. Pentru restore, opreste brokerii si reporneste-i
cu `-Dlab2.db=data/backups/lab2-backup.db` sau cu o copie noua a backup-ului.
Pasi detaliati si modurile scriptului sunt in [runbook](docs/runbook/README.md).

gRPC ofera contract tipizat, stub-uri generate si streaming; costa dependente/codegen,
instrumente speciale de inspectie si configurare suplimentara pentru browser/proxy.
HTTP/2 multiplexeaza stream-uri si foloseste framing binar; HTTP/1.1 obisnuit nu ofera
aceeasi multiplexare pe o conexiune. gRPC suporta unary, server streaming, client streaming
si bidirectional streaming; proiectul foloseste primele doua.

## Structura

```text
lab2-rpc-grpc-java/
├── contracts/
├── src/
│   ├── main/
│   │   ├── proto/
│   │   └── java/md/lab2/
│   │       ├── client/
│   │       ├── config/
│   │       ├── consumer/
│   │       ├── demo/
│   │       ├── logging/
│   │       ├── server/
│   │       ├── store/
│   │       └── tools/
│   └── test/java/md/lab2/
│       ├── unit/
│       ├── contract/
│       ├── integration/
│       └── failure/
├── deployment/
├── docs/
│   ├── adr/
│   ├── diagrams/
│   ├── evidence/
│   └── runbook/
├── scripts/
├── docker-compose.yml
├── DELIVERABLES.md
└── pom.xml
```

## Teste (cele mai relevante)

| Test | Ce demonstreaza |
|---|---|
| `ProtoContractTest` | Contractul folosit este identic; Subscribe=server-streaming; Unsubscribe=unary; ACK stages |
| `GrpcPublishSubscribeTest` | flux RPC real producer -> broker -> consumer -> ACK final |
| `TopicRoutingTest` | topic inexistent este respins |
| `LoadBalancingConcurrencyTest` | cereri concurente si distributie pe doua noduri |
| `ManySubscribersDoNotStarveUnaryRpcTest` | peste 8 stream-uri Subscribe deschise nu blocheaza Publish/ACK |
| `CrashBeforeFinalAckTest` | efect local=1 dupa crash si relivrare attempt=2 |
| `ProducerConfirmationLossTest` | brokerul persista, raspunsul se pierde, retry-ul nu dubleaza mesajul |
| `NackBackoffDlqTest` | NACK respecta backoff, apoi DLQ dupa attempt 3 |
| `BrokerRestartRecoveryTest` | livrarea in-flight ramane in SQLite si este recuperata dupa restart |
| `ConsumerFailoverTest` | stream-ul se reconecteaza la al doilea endpoint dupa caderea primului broker |
| `UnsubscribeIsolationTest` | consumer dezabonat nu primeste mesaje noi si nu poate ACK-ui |
| `StorageFailureTest` | eroarea de persistenta esueaza inchis, fara confirmare falsa |
| `BackupRestoreTest` | backup SQLite poate fi redeschis ca stare independenta |
| `BackupSafetyTest` | backup-ul nu poate suprascrie baza sursa |
| `BrokerRpcRestartTest` | server gRPC repornit livreaza mesajul neconfirmat din SQLite |
| `ConcurrentProcessingTest` | doi produceri + Inventory/Audit, prin brokeri cu notificatoare independente |
| `SubscriptionIsolationTest` | izolare consumer/topic, ACK/NACK owner gresit, ACK monotonic |
| `GrpcChannelPoolLifecycleTest` | un pool inchis nu poate crea din nou canale |
| `ConsumerAckTransportFailureTest` | raspuns ACK pierdut: reconnect, dedup si ACK final, fara NACK artificial |
| `DeliverySemanticsTest` | identitate Publish, DLQ permanent, ACK valid si ordering peste backoff |

## Documentatie

- Mapping cerinte -> cod: `STRUCTURE_MAPPING.md`
- Livrabile: `DELIVERABLES.md`
- ADR: `docs/adr/ADR-001-grpc-protobuf-sqlite.md`
- Diagrame: `docs/diagrams/`
- Scenarii de esec: `docs/evidence/failure-scenarios.md`
- Runbook: `docs/runbook/README.md`

## Referinte tehnice

- https://grpc.io/docs/what-is-grpc/core-concepts/
- https://grpc.io/docs/guides/deadlines/
- https://grpc.io/docs/guides/retry/
- https://grpc.io/docs/guides/custom-load-balancing/
