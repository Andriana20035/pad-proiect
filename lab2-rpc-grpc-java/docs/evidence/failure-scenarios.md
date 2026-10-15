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
- **Dovada RPC suplimentara:** `BrokerRpcRestartTest` opreste serverul si stream-ul real,
  construieste alt BrokerServer pe acelasi DB si verifica relivrarea/ACK final prin gRPC.

## 5. Unsubscribe si autorizarea ACK-ului

- **Stare initiala:** consumer abonat si o livrare in-flight.
- **Injectare:** Unsubscribe.
- **Observat:** mesajele publicate ulterior au routedTo=0 pentru acel consumer; ACK-ul lui
  este respins cu FAILED_PRECONDITION.
- **Test:** `UnsubscribeIsolationTest`.

`SubscriptionIsolationTest` verifica suplimentar ca alt consumer si alt topic raman active,
ca ACK/NACK pentru owner gresit sunt respinse si ca ACK RECEIVED/PROCESSED nu finalizeaza livrarea.

## 6. Eroare reala de scriere SQLite la Publish

`StorageFailureTest.failedSqliteWriteReturnsInternalAndNeverAcceptsThePublish` instaleaza
un trigger SQLite care respinge INSERT. Apelul gRPC raspunde INTERNAL; nu exista mesaj sau
delivery partial si nu se primeste accepted=true. Celalalt test acopera calea DB invalida.
Triggerul este injectare determinista de eroare SQL, nu o simulare fizica de power loss.

## 7. Caderea primului endpoint

`ConsumerFailoverTest` opreste broker-a dupa abonare, pastreaza clientii cu lista A,B si
verifica Publish prin retry catre broker-b, efect local unic si ACK PERSISTED.
`LoadBalancingConcurrencyTest` demonstreaza separat alternarea client-side si 40 Publish concurente.

## 8. Backup, restore si siguranta destinatiei

`BackupRestoreTest` deschide backup-ul ca store independent, cu mesajele si livrarile sale.
`BackupSafetyTest` respinge destinatia identica cu baza sursa si verifica pastrarea mesajelor.
Backup-ul foloseste VACUUM INTO; nu este replicare sau failover la pierderea discului comun.

## 9. Concurenta si notificari intre noduri

`ManySubscribersDoNotStarveUnaryRpcTest` pastreaza 12 stream-uri si verifica unary/ACK/COMPLETED.
`ConcurrentProcessingTest` publica 12 mesaje prin doi produceri concurenti pe broker-b,
in timp ce Inventory si Audit sunt conectati la broker-a: 24 completari, efecte locale=1,
DLQ=0. Notificatoarele nu sunt partajate: fallback-ul configurabil permite observarea DB comune.
Acesta este un test cu doua instante server in acelasi JVM; runbook-ul descrie si procese separate.

## 10. Raspuns ACK pierdut dupa un efect local reusit

`ConsumerAckTransportFailureTest` injecteaza UNAVAILABLE la primul ACK PROCESSED, folosind
un server gRPC de test. Consumerul se reconecteaza, deduplica efectul local si trimite ACK
PERSISTED. Verificarile cer doua livrari, un singur efect local si zero NACK-uri artificiale.
O eroare de transport la ACK nu este tratata ca esec de business al handlerului.
