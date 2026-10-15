# Dovezi reproductibile pentru scenariile de esec

Acest document nu contine capturi fabricate. Dovezile sunt testele automate si logurile
obtinute la rulare. Comanda comuna este:

```powershell
mvn clean test
```

## 1. Consumer cade dupa efectul local, inainte de ACK final

- **Stare initiala:** topic `orders.v1`, Inventory activ, mesaj nou.
- **Injectare:** `SoftCrashOnceInjector` sau `-Dlab2.hardCrashOnce=true`.
- **Observat:** efectul local este persistat; lipseste ACK PERSISTED; conexiunea dispare.
- **Recuperare:** lease-ul expira, brokerul aplica backoff, consumerul se reconecteaza la
  urmatorul endpoint si primeste acelasi messageId cu `attempt=2`.
- **Stare finala:** efect local=1, delivery COMPLETED, DLQ=0.
- **Garantie:** at-least-once la livrare + efect idempotent prin deduplicare persistenta.
- **Nu acopera:** exactly-once pentru sisteme externe care nu participa la tranzactia locala.
- **Test:** `CrashBeforeFinalAckTest`.

## 2. Brokerul persista, dar producerul nu primeste confirmarea

- **Stare initiala:** subscription activa.
- **Injectare:** serverul arunca `UNAVAILABLE` o singura data dupa commit.
- **Observat:** producerul nu primeste primul reply.
- **Recuperare:** ProducerClient reapeleaza `Publish` cu acelasi messageId.
- **Stare finala:** o singura inregistrare message si o singura delivery; reply-ul de retry
  are `duplicate=true`.
- **Garantie:** retransmiterea unui Publish este idempotenta pentru acelasi ID si continut.
- **Test:** `ProducerConfirmationLossTest`.

## 3. NACK repetat si DLQ

- **Stare initiala:** delivery attempt=1.
- **Injectare:** trei NACK-uri retryable.
- **Observat:** retry-ul nu este imediat; delay-ul creste exponential.
- **Recuperare:** attempt 2, apoi attempt 3.
- **Stare finala:** dupa esecul attempt 3, delivery este in DLQ persistenta.
- **Garantie:** retry limitat, fara bucla infinita.
- **Test:** `NackBackoffDlqTest`.

## 4. Restart broker cu livrare neconfirmata

- **Stare initiala:** delivery IN_FLIGHT in SQLite.
- **Injectare:** se reconstruieste brokerul/store-ul din acelasi fisier DB.
- **Observat:** starea nu dispare la restart.
- **Recuperare:** dupa expirarea lease-ului se replanifica cu backoff si se livreaza attempt=2.
- **Stare finala:** ACK PERSISTED -> COMPLETED.
- **Test:** `BrokerRestartRecoveryTest`.

## 5. Unsubscribe si autorizarea ACK-ului

- **Stare initiala:** consumer abonat si o livrare in-flight.
- **Injectare:** Unsubscribe.
- **Observat:** mesajele publicate ulterior au routedTo=0 pentru acel consumer; ACK-ul lui
  este respins cu FAILED_PRECONDITION.
- **Test:** `UnsubscribeIsolationTest`.
