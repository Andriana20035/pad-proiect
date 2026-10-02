# Legătura dintre fișierele Java și date

## Cine apelează pe cine

```mermaid
flowchart TD
    BM["BrokerMain.main"] --> MB["MinimalBroker"]
    BM --> SB["SocketBrokerServer"]
    BM --> MC["MessageConsole.run"]
    OM["OrderProducer.main"] --> MC
    MC --> OP["OrderProducer.sendOrderCreated"]
    OP --> ME["MessageEnvelope.orderCreated"]
    OP --> MV["MessageValidator.validate"]
    OP --> WM["WireMessage<br/>PUBLISH / PUBLISHED"]
    OP --> NET["Network<br/>conectare și deadline"]
    OP -->|"TCP: nu este apel Java"| SB
    SB -->|"publish / poll / ack / retryOrDlq"| MB
    MB --> CS["ConsumerState<br/>coadă per consumerId"]
    MB --> DE["Delivery / DeadLetter"]
    MB --> MV
    MB --> JS["JsonFileStore"]
    IC["InventoryConsumer"] -->|"moștenește bucla TCP"| ASC["AbstractSocketConsumer.run"]
    AC["AuditConsumer"] -->|"moștenește bucla TCP"| ASC
    ASC --> NET
    ASC --> WM
    ASC --> MV
    ASC -->|"TCP: SUBSCRIBE, ACK, NACK"| SB
    SB -->|"TCP: DELIVER"| ASC
    IC -->|"process"| IH["InventoryHandler.handle"]
    IH --> DS["DedupStore.apply"]
    AC -->|"process"| DS
    DS --> JS
    DS --> MV
    JS --> DISK[("Fișiere JSON pe disc")]
```

Săgețile „moștenește” arată o relație între clase, nu un apel de rețea.
AbstractSocketConsumer apelează process(message), iar implementarea concretă
aleasă este InventoryConsumer.process sau AuditConsumer.process.
Inventory și Audit folosesc instanțe separate de DedupStore și fișiere separate.

AppConfig furnizează configurația componentelor. LogUtil este apelat în producer,
broker și consumatori pentru a afișa același messageId și correlationId.
Aceste utilitare sunt omise din unele legături pentru a păstra diagrama lizibilă.

## Responsabilități

| Fișier | Ce face |
|---|---|
| MessageConsole.java | Citește datele, verifică introducerea și alege destinatarul |
| OrderProducer.java | Creează și validează mesajul, trimite PUBLISH, verifică PUBLISHED |
| MessageEnvelope.java | Definește câmpurile și generează identități stabile din numărul comenzii |
| WireMessage.java | Definește PUBLISH, SUBSCRIBE, SUBSCRIBED, DELIVER, PUBLISHED, ACK și NACK |
| MessageValidator.java | Verifică versiunea, ID-urile, timestamp-ul și produsul/cantitatea |
| SocketBrokerServer.java | Primește JSON prin TCP, gestionează abonamentele și așteaptă confirmări |
| MinimalBroker.java | Gestionează solicitări, rutare, cozi, in-flight, retry, ACK și DLQ |
| ConsumerState.java | Conține canalul și coada unui consumator |
| AbstractSocketConsumer.java | Primește DELIVER, apelează process și trimite ACK/NACK |
| InventoryHandler.java | Înregistrează efectul prin DedupStore și poate injecta crash înainte de ACK |
| DedupStore.java | Salvează atomic efectul și identitatea; ignoră repetarea aceleiași solicitări |
| AuditConsumer.java | Salvează evenimentul de audit prin propriul DedupStore |
| JsonFileStore.java | Citește snapshot-uri și salvează prin fișier temporar + înlocuire atomică |
| Network.java | Conectare cu buget explicit și închiderea socket-ului la deadline |
| AppConfig.java | Citește deployment/application.properties și suprascrierile JVM |
| LogUtil.java | Afișează logurile corelate |

## Ce se află în RAM și pe disc

```mermaid
flowchart LR
    Q["RAM broker<br/>cozi, solicitări, in-flight, DLQ"] -->|"save după tranziții"| J["JsonFileStore.write"]
    J --> F[("data/broker.json")]
    F -->|"JsonFileStore.read la pornire"| Q
    IE["DedupStore Inventory<br/>comenzi aplicate"] -->|"commit atomic"| IF[("data/inventory.json")]
    AE["DedupStore Audit<br/>evenimente înregistrate"] -->|"commit atomic"| AF[("data/audit.json")]
    IF -->|"citire la pornire și înainte de apply"| IE
    AF -->|"citire la pornire și înainte de apply"| AE
```

În broker.json, requests păstrează solicitările, routes destinațiile,
consumers cozile și inFlight, iar deadLetters livrările eșuate.
În inventory.json și audit.json, applied este o mapare de la numărul comenzii
la mesajul aplicat. Pentru Inventory, înregistrarea însăși reprezintă efectul local:
effectCount este 1 dacă acea comandă există și 0 dacă nu există.

După ACK dispare livrarea din inFlight, dar solicitarea rămâne în istoric
pentru a preveni o nouă publicare a aceleiași comenzi.
Datele rămân după restart dacă fișierele sunt păstrate.
Salvarea consolei într-un log ajută diagnosticul; reluarea livrărilor folosește snapshot-urile JSON.

## Identificatori diferiți

| Câmp | Exemplu / sens |
|---|---|
| key | ORD-1001: identitatea solicitării/comenzii |
| id | UUID stabil pentru evenimentul ORDER_CREATED al acelei comenzi |
| correlationId | UUID stabil al fluxului, propagat în mesaj și afișat în loguri |
| timestamp | Momentul creării mesajului; brokerul păstrează primul mesaj acceptat |
| schemaVersion | 1.0: versiunea contractului acceptat |

Producerul derivează ID-urile din numărul comenzii. Aceleași date și aceeași destinație
pot fi retrimise fără efect nou; datele/destinația schimbate sub același număr sunt respinse.
