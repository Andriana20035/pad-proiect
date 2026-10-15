# Garantii, limite si scalare

## Garantii demonstrate

- Publish confirmat doar dupa commit SQLite.
- Retransmiterea aceluiasi `messageId` nu creeaza al doilea mesaj.
- O delivery este finala doar la ACK `PERSISTED`.
- Lipsa ACK final produce retry dupa lease + backoff.
- Maximum 3 incercari; apoi DLQ.
- Efectul local al consumerului este deduplicat persistent.
- FIFO practic per consumer/topic deoarece exista maximum o delivery IN_FLIGHT pentru
  acea pereche; retry-ul acelei livrari trebuie rezolvat inaintea urmatoarei.
- Consumerul dezabonat nu primeste livrari noi.

## Ce NU promitem

- Exactly-once pentru efecte externe.
- Consens sau replicare reala intre nodurile broker.
- HA la defectarea discului comun.
- Rebalansarea unui server-streaming RPC deja deschis.
- Scalare nelimitata cu SQLite.

## Ce se intampla la peste un milion de mesaje

Latenta poate creste din cauza dimensiunii fisierului DB, indexurilor, fsync-urilor,
contentiei pe writer lock si scanarilor. Proiectul reduce problema prin WAL, indexuri si
tranzactii scurte, dar SQLite ramane un singur writer logic.

Pentru volum mare, solutia ar evolua astfel:

1. retentie/archivare pentru mesaje COMPLETED si DLQ veche;
2. batch writes si batch ACK unde semantica permite;
3. partitionare/sharding pe topic/key;
4. baza server externa (PostgreSQL) sau log distribuit (Kafka) pentru persistenta;
5. mai multe canale gRPC pentru foarte multe stream-uri concurente;
6. metrici p95/p99, queue depth, retry rate si backpressure;
7. load balancer/service discovery real, nu lista statica de endpoint-uri.

In gRPC, stream-urile lungi reduc costul initierii repetate de RPC-uri, dar un stream deja
deschis ramane legat de conexiunea/backend-ul ales. La incarcare foarte mare se pot folosi
pool-uri de canale si partitionarea consumatorilor.
