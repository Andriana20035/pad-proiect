# Runbook Lab 2

## 1. Import in IntelliJ

1. Extrage folderul `lab2-rpc-grpc-java`.
2. IntelliJ -> File -> Open -> selecteaza `pom.xml` sau folderul proiectului.
3. Trust Project.
4. Project SDK: JDK 22 este OK; proiectul compileaza cu release 17.
5. Asteapta Maven Sync; la prima rulare se genereaza clasele gRPC din `.proto`.

Daca IDE-ul nu vede clasele `md.lab2.rpc.v1`, executa Maven `compile` si apoi Reload All
Maven Projects. Nu copia manual clase generate in `src/main/java`.

## 2. Verificarea obligatorie

```powershell
mvn clean test
```

Trebuie sa se termine cu `BUILD SUCCESS` si fara failures/errors.

Apoi:

```powershell
mvn -DskipTests package
```

Se creeaza local `target/lab2-rpc-grpc-1.0.0-all.jar`. `target/` nu se comite.

## 3. Demo automat

```powershell
java -cp target/lab2-rpc-grpc-1.0.0-all.jar md.lab2.demo.DemoMain
```

Demo-ul porneste doua broker nodes cu o baza temporara, 2 consumers si producerul.
Inventory face o singura cadere controlata dupa efectul local.

## 4. Procese separate

### Terminal 1 - Broker A

```powershell
java -Dlab2.port=50051 -Dlab2.node=broker-a -Dlab2.db=data/lab2-broker.db `
  -cp target/lab2-rpc-grpc-1.0.0-all.jar md.lab2.server.BrokerMain
```

### Terminal 2 - Broker B

```powershell
java -Dlab2.port=50052 -Dlab2.node=broker-b -Dlab2.db=data/lab2-broker.db `
  -cp target/lab2-rpc-grpc-1.0.0-all.jar md.lab2.server.BrokerMain
```

### Terminal 3 - creeaza topicul

```powershell
java -Dlab2.endpoints=localhost:50051,localhost:50052 `
  -cp target/lab2-rpc-grpc-1.0.0-all.jar md.lab2.demo.TopicAdminMain orders.v1
```

Acest proces se inchide imediat dupa confirmare.

### Terminal 4 - Inventory

```powershell
java -Dlab2.endpoints=localhost:50051,localhost:50052 `
  -cp target/lab2-rpc-grpc-1.0.0-all.jar md.lab2.demo.InventoryConsumerMain
```

### Terminal 5 - Audit

```powershell
java -Dlab2.endpoints=localhost:50051,localhost:50052 `
  -cp target/lab2-rpc-grpc-1.0.0-all.jar md.lab2.demo.AuditConsumerMain
```

### Terminal 6 - Producer

```powershell
java -Dlab2.endpoints=localhost:50051,localhost:50052 `
  -cp target/lab2-rpc-grpc-1.0.0-all.jar md.lab2.demo.OrderProducerMain ORD-3001
```

## 5. Scenariul real de crash al consumerului

Sterge doar markerul vechi inainte de o demonstratie noua:

```powershell
Remove-Item data/inventory-hard-crash.marker -ErrorAction SilentlyContinue
```

Porneste Inventory cu:

```powershell
java -Dlab2.hardCrashOnce=true `
  -Dlab2.endpoints=localhost:50051,localhost:50052 `
  -cp target/lab2-rpc-grpc-1.0.0-all.jar md.lab2.demo.InventoryConsumerMain
```

Trimite un order nou. Inventory va iesi cu code 17 DUPA ce baza locala a salvat efectul,
dar INAINTE de ACK PERSISTED. Repornieste Inventory fara `hardCrashOnce`. Dupa lease si
backoff, mesajul este relivrat, apare `DEDUP_SKIP`, apoi ACK PERSISTED.

## 6. Test simplu de failover

Cu ambii brokeri porniti si consumerii activi:

1. Opreste Broker A (Ctrl+C).
2. Nu opri consumerii.
3. ConsumerClient va primi UNAVAILABLE, va aplica backoff si va selecta urmatorul endpoint.
4. Trimite un order nou; Broker B il accepta.
5. Reporneste Broker A. Producerul continua round-robin.

## 7. Test topic gresit

In `OrderProducerMain`, schimba temporar topicul la `orders.v999` fara CreateTopic sau
foloseste testul `TopicRoutingTest`. Sistemul trebuie sa raspunda `NOT_FOUND`; nu trebuie sa
creeze implicit un topic la publish.

## 8. Backup

```powershell
New-Item -ItemType Directory -Force data/backups | Out-Null
java -Dlab2.db=data/lab2-broker.db `
  -cp target/lab2-rpc-grpc-1.0.0-all.jar `
  md.lab2.tools.StoreMaintenanceMain backup data/backups/lab2-backup.db
```

Testul `BackupRestoreTest` verifica automat ca backup-ul poate fi deschis separat.

## 9. Ce loguri arati profesoarei

Urmariti acelasi `messageId` si `correlationId` prin:

```text
PERSISTED -> DELIVER attempt=1 -> ACK_RECEIVED -> LOCAL_EFFECT_PERSISTED
-> ACK_PROCESSED -> CRASH_BEFORE_FINAL_ACK -> DELIVER attempt=2
-> DEDUP_SKIP -> ACK_PERSISTED
```

Pentru load balancing, doua Publish consecutive trebuie sa arate alternativ `broker-a` si
`broker-b` cand ambele sunt disponibile.
