# Laborator 1 PAD — agent de mesagerie

Client producer → broker TCP → Inventory și Audit → ACK/NACK.
Proiectul demonstrează mesagerie asincronă, retry limitat, deduplicare persistentă,
FIFO și DLQ. Nu include HTTP, cache sau serviciu extern: acestea apar în incrementurile următoare.

## Pornire și verificare

Necesită JDK 17 sau mai nou și Maven. În IntelliJ deschide pom.xml ca proiect Maven;
working directory trebuie să fie rădăcina proiectului.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-demo.ps1 -Mode tests
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-demo.ps1
```

DemoMain oprește prima instanță Inventory înainte de ACK, construiește una nouă
din datele persistente și verifică effectCount=1, DLQ=0 și retrimiterea aceleiași solicitări.
Scriptul salvează logul corelat în data/demo.log. Datele demonstrației sunt într-un
subdirector data/demo-* nou la fiecare rulare; nu modifică datele rulării interactive.
Testul ProcessCrashTest oprește chiar procesul JVM după efect și îl repornește.

Pentru procese separate, rulează în patru terminale:

Dacă politica PowerShell blochează scripturile, folosește același prefix
`powershell -NoProfile -ExecutionPolicy Bypass -File` înaintea comenzilor de mai jos.
Acesta permite scriptul numai în procesul respectiv, fără schimbarea politicii globale.

```powershell
.\scripts\run-demo.ps1 -Mode broker
.\scripts\run-demo.ps1 -Mode inventory
.\scripts\run-demo.ps1 -Mode audit
.\scripts\run-demo.ps1 -Mode producer
```

Nu porni DemoMain simultan cu BrokerMain pe același port.
Pentru cădere reală, pornește Inventory cu -Crash; după oprirea sa,
repornește-l fără -Crash. Trimite comanda din producer.

## Contract și identitate

MessageEnvelope conține id, timestamp, correlationId, schemaVersion, type, key și payload.
key este numărul unic al comenzii. Producerul derivă ID-ul mesajului și correlation ID
din acest număr: aceeași comandă retrimisă păstrează identitatea.
Brokerul acceptă repetarea cu aceleași date și aceeași destinație fără cozi noi.
Același număr cu alte date/destinație este respins. Pentru o comandă nouă folosește un număr nou.
Timestamp-ul original rămâne în înregistrarea brokerului.
MessageValidator verifică explicit contractul v1 și JSON-ul din payload la producer,
broker și consumatori; fișierul contracts/order-message-v1.schema.json descrie același contract.
Schema nu este interpretată dinamic: schimbarea ei cere actualizarea validatorului și testelor.

Canalul aplicației este fix: orders. Publicarea poate fi unul-la-unu, cu consumerId,
sau către toți abonații. La broadcast fiecare consumator primește mesajul integral.
Producerul nu cunoaște adresa consumatorilor. PUBLISHED confirmă acceptarea durabilă
la broker; ACK confirmă efectul unui consumator.

## Date și garanții

Configurația este în deployment/application.properties și poate fi suprascrisă
prin -Dapp.config sau proprietăți JVM individuale. Nu sunt necesare secrete.

- data/broker.json: solicitări acceptate, destinații, cozi, livrări neconfirmate și DLQ.
- data/inventory.json: comenzile aplicate; fiecare înregistrare reprezintă efectul local
  și dovada de deduplicare, salvate atomic împreună.
- data/audit.json: evenimentele de audit, salvate cu aceeași protecție.
- Fișierele .lock protejează actualizările consumatorilor; .tmp este folosit la salvarea atomică.

Inventory simulează înregistrarea unei comenzi, nu o gestiune completă de stoc.
Audit chiar salvează evenimentul pe disc. Datele rămân după restart.
Coada goală nu înseamnă procesare terminată: poate exista o livrare în curs.

FIFO se aplică per consumator/canal, în ordinea acceptării de broker.
Brokerul permite o singură conexiune activă per consumerId și o livrare în curs.
Retry-ul revine în fața cozii; nu există ordine globală între consumatori.
Limita implicită este 3 încercări totale: prima livrare și două retry-uri.
NACK retryable=true, timeout și deconectarea permit retry; o eroare permanentă
merge direct în DLQ. La restart, o livrare neconfirmată consumă următoarea încercare.
Un consumator absent nu consumă încercări prin simpla așteptare.

Politica urmează at-least-once cu încercări limitate și deduplicare:
livrările se pot repeta, efectul local este idempotent.
Nu promite procesarea fiecărui mesaj: eșecurile definitive sunt păstrate în DLQ.
At-most-once ar elimina mesajul înainte de efect/fără retry, riscând pierderea la crash.
Nu declarăm exactly-once pentru efecte externe.

Stocarea este didactică: un singur broker per director de date, fișiere locale,
snapshot complet la fiecare tranziție. Nu există replicare, retenție sau limitare de memorie.
Salvarea refuză să continue dacă discul eșuează; un fișier corupt nu este înlocuit cu stare goală.
Recuperarea presupune disc disponibil și înlocuire atomică suportată de sistemul de fișiere;
nu garantăm recuperare după defectarea discului sau pierderea alimentării.

## Structura și locurile din cod

Structura recomandată este păstrată prin layout-ul standard Maven, conform
[STRUCTURE_MAPPING.md](STRUCTURE_MAPPING.md):

```text
src/main/java/md/lab/
  api/                 contractul și validarea
  orderservice/        client producer
  consumers/           Inventory, Audit și efectele persistente
  integrationservice/  broker, adaptor TCP, stocare, configurație
src/test/java/md/lab/
  unit/ integration/ contract/ failure/
contracts/
deployment/
docs/diagrams/
docs/adr/
docs/runbook/
scripts/
docker-compose.yml
```

În IntelliJ: Ctrl+N pentru clasă, Ctrl+F pentru metodă.

| Cerință | Cod | Verificare |
|---|---|---|
| Identitate stabilă și metadate | MessageEnvelope.orderCreated | ValidationTest |
| Validarea contractului | MessageValidator.validate | ValidationTest, SocketPolicyTest |
| Trimitere TCP și PUBLISHED | OrderProducer.sendOrderCreated | TargetedRoutingTest |
| Rutare și publicare idempotentă | MinimalBroker.publish | BrokerRoutingTest, TargetedRoutingTest |
| Stocare concurentă și FIFO | ConsumerState, MinimalBroker.poll | RecoveryTest, SocketPolicyTest |
| ACK după efect și NACK | AbstractSocketConsumer.run | ProcessCrashTest, SocketPolicyTest |
| Retry/DLQ | MinimalBroker.retryOrDlq | DlqTest, RecoveryTest, SocketPolicyTest |
| Efect și deduplicare atomică | DedupStore.apply, InventoryHandler.handle | DedupStoreTest, ProcessCrashTest |
| Recuperare broker | MinimalBroker constructor | RecoveryTest |
| Eșecul salvării pe disc | JsonFileStore, DedupStore.apply | StorageFailureTest |
| Deadline | Network, OrderProducer, SocketBrokerServer | SocketPolicyTest, DeadlineTest |
| Log corelat | LogUtil.log | ProcessCrashTest, data/demo.log |

Procedura detaliată: [runbook](docs/runbook/README.md).
Decizii și limite: [ADR](docs/adr/ADR-001-messaging-guarantee.md).
Diagramă: [secvență](docs/diagrams/sequence.md).
Docker este o alternativă de pornire, descrisă în deployment/README.md.
