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
- teste unit, contract, integration si failure.

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
durata si **nu primeste deadline scurt**. Daca brokerul cade, stream-ul se inchide, iar
ConsumerClient se reconecteaza cu backoff si selecteaza urmatorul endpoint.

## Load balancing

ProducerClient foloseste round-robin intre endpoint-urile din `LAB2_ENDPOINTS` / 
`-Dlab2.endpoints`. Astfel apelurile unary sunt distribuite intre broker-a si broker-b.
Un stream `Subscribe` ramane pe nodul pe care a fost deschis; la deconectare, consumerul
alege urmatorul endpoint. Aceasta este o limita intentionata si este documentata in ADR.

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
| `CrashBeforeFinalAckTest` | efect local=1 dupa crash si relivrare attempt=2 |
| `ProducerConfirmationLossTest` | brokerul persista, raspunsul se pierde, retry-ul nu dubleaza mesajul |
| `NackBackoffDlqTest` | NACK respecta backoff, apoi DLQ dupa attempt 3 |
| `BrokerRestartRecoveryTest` | livrarea in-flight ramane in SQLite si este recuperata dupa restart |
| `ConsumerFailoverTest` | stream-ul se reconecteaza la al doilea endpoint dupa caderea primului broker |
| `UnsubscribeIsolationTest` | consumer dezabonat nu primeste mesaje noi si nu poate ACK-ui |
| `StorageFailureTest` | eroarea de persistenta esueaza inchis, fara confirmare falsa |
| `BackupRestoreTest` | backup SQLite poate fi redeschis ca stare independenta |

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