# Diagrame de secvență — fluxul normal

Diagramele arată obiectele din fișierele Java și mesajele efectiv transmise.
Săgețile TCP separă procesele; celelalte reprezintă apeluri locale.

## 1. Abonarea unui consumator

```mermaid
sequenceDiagram
    autonumber
    participant C as AbstractSocketConsumer.run
    participant S as SocketBrokerServer
    participant B as MinimalBroker
    C->>S: TCP: SUBSCRIBE(consumerId, orders)
    S->>B: registerConsumer(consumerId, orders)
    B-->>S: consumator înregistrat
    S->>S: verifică o singură conexiune activă pentru ID
    alt ID disponibil
        S-->>C: TCP: SUBSCRIBED
        Note over C,S: Conexiunea rămâne deschisă pentru DELIVER și ACK/NACK
    else ID deja conectat
        S-->>C: TCP: NACK(Consumer already connected)
        Note over C: Așteaptă înainte de reconectare
    end
```

BrokerMain preînregistrează Inventory și Audit. Mesajele lor pot aștepta în cozi
și dacă procesele consumatoare nu sunt încă pornite.

## 2. Introducerea și publicarea comenzii

```mermaid
sequenceDiagram
    autonumber
    actor U as Utilizator
    participant C as MessageConsole
    participant P as OrderProducer
    participant E as MessageEnvelope
    participant V as MessageValidator
    participant S as SocketBrokerServer
    participant B as MinimalBroker
    participant F as JsonFileStore
    U->>C: Destinatar, ORD-1001, Laptop, 1
    C->>C: verifică datele introduse și formează payload JSON
    C->>P: sendOrderCreated(orderId, payload, destination)
    activate P
    P->>E: orderCreated(orderId, payload)
    E-->>P: mesaj cu id, timestamp, correlationId, schemaVersion
    P->>V: validate(message)
    V-->>P: valid
    P->>S: TCP: WireMessage PUBLISH pe orders
    activate S
    S->>B: publish(channel, message, consumerId)
    activate B
    B->>V: validate(message)
    V-->>B: valid
    B->>B: verifică istoricul solicitării și selectează destinatarii
    B->>B: adaugă Delivery(message, attempt=1) în fiecare coadă
    B->>F: write(broker.json, snapshot)
    F-->>B: commit atomic încheiat
    B-->>S: numărul destinatarilor
    deactivate B
    S-->>P: TCP: PUBLISHED(messageId)
    deactivate S
    P-->>C: message
    deactivate P
    Note over P,B: PUBLISHED confirmă acceptarea durabilă, nu procesarea de către consumatori
```

În cazul unei repetări identice, brokerul recunoaște solicitarea și răspunde PUBLISHED
fără a adăuga livrări noi. Dacă numărul există cu alte date sau altă destinație,
publicarea este respinsă prin NACK.

## 3. Livrarea către Inventory și confirmarea efectului

```mermaid
sequenceDiagram
    autonumber
    participant S as SocketBrokerServer
    participant B as MinimalBroker
    participant F as JsonFileStore
    participant C as AbstractSocketConsumer.run
    participant I as InventoryConsumer
    participant H as InventoryHandler
    participant D as DedupStore Inventory
    S->>B: poll(inventory-consumer, timeout)
    B->>B: scoate primul mesaj din coadă și îl marchează in-flight
    B->>F: write(broker.json, snapshot)
    F-->>B: salvat
    B-->>S: Delivery(message, attempt)
    S->>C: TCP: DELIVER(message)
    C->>C: validate(message)
    C->>I: process(message)
    I->>H: handle(message)
    H->>D: apply(message)
    D->>D: verifică solicitarea aplicată și protejează accesul la fișier
    D->>F: write(inventory.json, applied)
    F-->>D: efect + deduplicare salvate împreună
    D-->>H: true: efect nou
    H-->>I: procesare încheiată
    I-->>C: succes
    C-->>S: TCP: ACK(consumerId, messageId)
    S->>B: ack(consumerId, delivery)
    B->>B: elimină in-flight
    B->>F: write(broker.json, snapshot)
    F-->>B: confirmare salvată
```

AbstractSocketConsumer.run este metoda moștenită de InventoryConsumer.
process este metoda implementată în clasa concretă.
LOCAL_EFFECT_DONE apare după commit-ul local; ACK este trimis după revenirea din process.

## 4. Ramura Audit și independența consumatorilor

```mermaid
sequenceDiagram
    autonumber
    participant S as SocketBrokerServer
    participant I as InventoryConsumer
    participant A as AuditConsumer
    participant D as DedupStore Audit
    participant F as JsonFileStore
    par Inventory
        S->>I: TCP: DELIVER prin bucla moștenită
        I->>I: efect local în inventory.json
        I-->>S: TCP: ACK
    and Audit
        S->>A: TCP: DELIVER prin bucla moștenită
        A->>D: apply(message)
        D->>F: write(audit.json, applied)
        F-->>D: audit + deduplicare salvate
        D-->>A: true sau false dacă exista deja
        A->>A: AUDIT_WRITTEN sau DEDUP_SKIP în log
        A-->>S: TCP: ACK
    end
```

ACK este emis de AbstractSocketConsumer după succesul metodei process.
Ordinea dintre Inventory și Audit poate varia. Chiar și o livrare poate începe
înainte ca producerul să afișeze PUBLISHED, deoarece conexiunile sunt servite concurent.
FIFO este per consumator; nu există ordonare globală între cele două ramuri.
