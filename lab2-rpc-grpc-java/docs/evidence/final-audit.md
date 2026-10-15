# Audit final Lab 2 - 2026-10-15

## A. Verdict

Ready for evaluation: **YES pentru proiectul local verificat**. Acoperire aproximativa a
listei academice: **95-100%**, estimare de checklist, nu procent de code coverage masurat.
Predarea Git mai necesita includerea modificarilor si fisierelor noi intr-un commit.
Nu s-a creat tag; `lab2-v1.0` este amanat pana la confirmarea profesoarei.

Mediu verificat: Windows 11, Oracle JDK 22.0.2, Maven 3.9.16, compilation release 17.
Contractele nu au fost modificate; au acelasi SHA-256:
`13DED355B8DA8792CA78105838A6047FBE71454D81C597FFDFE512857A871CE2`.

## B. Cerinte si dovezi

| # | Cerinta | Implementata | Cod/document | Test/dovada | Observatii |
|---|---|---|---|---|---|
| 1 | Contract Protobuf | Da | messaging_rpc.proto; contracts/ | ProtoContractTest | Copii identice |
| 2 | gRPC HTTP/2 real | Da | BrokerServer; ManagedChannelBuilder | RPC integration + demo multiproces | Plaintext local |
| 3 | Operatii unary | Da | MessagingService; RpcMessageService | ProtoContractTest | 8 unary |
| 4 | Subscribe server-streaming | Da | subscribe/runSubscription | ProtoContractTest | Fara deadline scurt |
| 5 | Inventory si Audit | Da | InventoryConsumerMain; AuditConsumerMain | ConcurrentProcessingTest; DemoMain | Baze locale distincte |
| 6 | Producer RPC | Da | ProducerClient; OrderProducerMain | GrpcPublishSubscribeTest | Topic creat separat |
| 7 | Broker intermediar | Da | BrokerServer; RpcMessageService | Integration tests | Doua noduri locale |
| 8 | Cele 7 campuri mesaj | Da | Envelope; validateEnvelope | ProtoContractTest | schemaVersion=1.0 |
| 9 | ACK/NACK | Da | ack/nack; ConsumerClient | NackBackoffDlqTest; SubscriptionIsolationTest | Owner validat |
| 10 | 3 stadii ACK | Da | AckStage; SqliteMessageStore.ack | SubscriptionIsolationTest | Monotonic; doar PERSISTED finalizeaza |
| 11 | Retry limitat | Da | maxAttempts; claimNextDelivery | NackBackoffDlqTest | 3 implicit |
| 12 | Backoff | Da | BackoffPolicy | BackoffPolicyTest; NackBackoffDlqTest | Exponential, plafonat |
| 13 | DLQ persistenta | Da | dlq; moveToDlq | NackBackoffDlqTest; DeliverySemanticsTest | NACK permanent merge direct |
| 14 | Deduplicare | Da | ConsumerLocalStore.applyOnce | CrashBeforeFinalAckTest | Efect + ID intr-o tranzactie |
| 15 | Ordering declarat | Da | selectDueDeliveryId; README | DeliverySemanticsTest | Fara FIFO strict peste retry |
| 16 | Persistenta reala | Da | SqliteMessageStore; ConsumerLocalStore | Restart/backup tests | SQLite WAL + FULL |
| 17 | Log corelat | Da | CorrelatedLog; deliveryId in ACK broker | DemoMain; loguri runtime | ACK client contine messageId/correlationId |
| 18 | Failover | Da | ConsumerClient.run; ProducerClient.publish | ConsumerFailoverTest; multiproces | DB comuna, fara replicare |
| 19 | Load balancing | Da | RoundRobinEndpointPool | LoadBalancingConcurrencyTest | Cursor per client |
| 20 | Topic routing | Da | createDeliveriesForActiveSubscribers | TopicRoutingTest; SubscriptionIsolationTest | Filtru topic/active |
| 21 | CreateTopic explicit | Da | TopicAdminMain; createTopic | TopicRoutingTest | Publish nu creeaza topic |
| 22 | Unsubscribe | Da | deactivateSubscription | UnsubscribeIsolationTest | Pereche consumer/topic |
| 23 | Deadline unary | Da | blockingStub/unaryStub | RPC tests | 2000 ms implicit |
| 24 | Stream fara deadline scurt | Da | ConsumerClient.run | Crash/reconnect tests; cod | Stub de streaming fara withDeadlineAfter |
| 25 | Crash dupa efect local | Da | SoftCrashOnceInjector; HardCrashOnceInjector | CrashBeforeFinalAckTest; exit 17 real | Inainte de PERSISTED |
| 26 | Redelivery + dedup + ACK | Da | run; applyOnce; ack | CrashBeforeFinalAckTest; multiproces | DEDUP_SKIP |
| 27 | Accepted vs processed | Da | PublishReply; ACK final; README | GrpcPublishSubscribeTest; SubscriptionIsolationTest | Publish confirma doar commit broker |
| 28 | At-least-once | Da, limitat | lease/retry + README | CrashBeforeFinalAckTest | Esec repetat -> DLQ |
| 29 | At-most-once comparativ | Da, documentat | README | Inspectie documentatie | Nu este mod implementat |
| 30 | Backup/restore | Da | backupTo; StoreMaintenanceMain | BackupRestoreTest; runtime backup redeschis | VACUUM INTO |
| 31 | Restart broker | Da | BrokerServer nou, acelasi DB | BrokerRpcRestartTest | Server/stream real |
| 32 | Producer confirmation loss | Da | PublishFailureInjector; publish idempotent | ProducerConfirmationLossTest | Acelasi Envelope reutilizat |
| 33 | Storage failure fara accepted | Da | commit inainte de reply; toStatus | StorageFailureTest | Trigger SQLite abort -> INTERNAL |
| 34 | Unit/integration/contract/failure | Da | src/test/java/md/lab2/ | 28 teste | Niciun test eliminat |
| 35 | Diagrama arhitectura | Da | architecture.mmd | Inspectie semantica | Brokerul transmite stream-ul |
| 36 | Secventa normala | Da | sequence-normal.mmd | Inspectie | CreateTopic -> Subscribe -> Publish |
| 37 | Secventa failure | Da | sequence-failure.mmd | Inspectie | Abonare inainte de Publish |
| 38 | Runbook | Da | docs/runbook/README.md | Script + demo executate | JDK 22 |
| 39 | Dovezi reproductibile | Da | failure-scenarios.md; teste | Loguri locale audit | Nu sunt capturi fabricate |
| 40 | Garantii/limite | Da | README; ADR-001 | Inspectie corelata cu codul | Nu este HA/non-blocking complet |

## C. Feedback profesoara

| Problema veche | Solutie verificata | Cod | Dovada |
|---|---|---|---|
| 8 stream-uri ocupau pool-ul RPC | Executori cached separati pentru RPC si subscriptions | BrokerServer | ManySubscribersDoNotStarveUnaryRpcTest |
| Subscribe retinea firul gRPC | Valideaza/activeaza, submit pe subscriptionExecutor, return | RpcMessageService.subscribe | 12 active + Health/Publish/ACK |
| Polling SQLite cu sleep(100) | SubscriptionNotifier, Condition/version, signal/signalAll | runSubscription; awaitChange | Testul cu 12 si ConcurrentProcessingTest |
| Modificari din alt broker | crossNodeRefreshMs configurabil | nextWakeDelayMs; runSubscription | Produceri pe B, consumeri pe A |
| Lease expirat dupa contention | Timpul calculat dupa BEGIN IMMEDIATE | claimNextDelivery; ack; nack | Concurenta + DLQ=0 |
| Cleanup JUnit esua | shutdownNow, signal, await server si ambii executors; close/interrupt/join | BrokerServer; GrpcChannelPool; teste | Toate testele cu cleanup activ |

Fiecare Subscribe activ foloseste in continuare un worker elastic. Nu exista admission
control/limita de memorie pentru stream-uri nelimitate. Nu se pretinde non-blocking complet.

## D. Probleme confirmate si corectate in audit

| Severitate | Fisier | Problema | Corectie/dovada |
|---|---|---|---|
| Critical | SqliteMessageStore.backupTo | Aceeasi cale sursa/destinatie stergea DB inainte de backup | Refuz inainte de delete, inclusiv Files.isSameFile; BackupSafetyTest |
| High | GrpcChannelPool; ConsumerClient.run | Close putea fi urmat de recrearea unui canal, inclusiv cursa cu reconnect | Pool inchis definitiv, acces sincronizat, channel() in try; lifecycle regression |
| High | ConsumerClient.handleDelivery | Eroarea transportului la ACK era tratata ca esec handler si producea NACK | Separare procesare/ACK; ConsumerAckTransportFailureTest |
| Medium | ConsumerClient.run | Backoff reconnect se reseta inainte sa fie primita o livrare | Reset doar dupa primirea unei livrari |
| Medium | DemoMain | Cleanup doar pe traseul de succes, stop in loc de close | finally, close/interrupt/join, verificari de rezultat si terminare |
| Medium | ConsumerLocalStore; SqliteMessageStore | Configurare esuata dupa open putea lasa Connection deschisa; FULL doar in initializare | Close la exceptie, FULL explicit pe fiecare conexiune |
| Medium | OrderProducerMain | CreateTopic automat ascundea typo-ul de topic | Creare numai prin admin; Publish returneaza NOT_FOUND |
| High | run-lab2.ps1 | Repackage la fiecare mod putea incerca suprascrierea JAR-ului deschis pe Windows | Reutilizare JAR, package explicit, parametrizare cai/porturi, exit code propagat |
| Medium | pom.xml | Shaded JAR fara Main-Class | Manifest DemoMain; java -jar executat cu succes |
| Medium | Teste failure/concurenta | Storage failure doar la constructor; restart doar la store; concurenta producerilor fara procesare | Teste reale gRPC suplimentare, doi produceri + doi consumatori |
| Low | Documentatie/diagrame | Ordering, at-most-once, scalare si restore insuficient descrise; Subscribe dupa Publish in diagrama failure | README/runbook/evidence/mapping actualizate; diagrama corectata |
| Low | .gitignore/.dockerignore | Protectie incompleta pentru credentiaIe/context Docker | Reguli env/keys; excludere target/data/.git din context |

Nicio problema Critical/High cunoscuta nu a ramas deschisa in scopul laboratorului local.
Limite ramase: HTTP/2 plaintext fara autentificare, single-writer SQLite, disc comun,
worker per stream, retry limitat, fara FIFO strict peste retry, fara DLQ redrive, fara
benchmark de un milion de mesaje. Health este liveness RPC, nu write-readiness SQLite.
Shutdown poate raporta timeout dupa circa 3 secunde daca o operatie externa nu se opreste.

## E. Validari executate

| Comanda/verificare | Tests run | Failures | Errors | Skipped | Rezultat |
|---|---:|---:|---:|---:|---|
| mvn clean test | 28 | 0 | 0 | 0 | BUILD SUCCESS |
| mvn -Dtest=ManySubscribersDoNotStarveUnaryRpcTest test | 1 | 0 | 0 | 0 | BUILD SUCCESS |
| mvn -Dtest=ConsumerFailoverTest test | 1 | 0 | 0 | 0 | BUILD SUCCESS |
| verify-deliverables.ps1 | 28 | 0 | 0 | 0 | All Lab 2 deliverables and tests are OK. |
| mvn package | 28 | 0 | 0 | 0 | BUILD SUCCESS |
| run-lab2.ps1 -Mode package | - | - | - | - | BUILD SUCCESS |
| java -jar ...-all.jar | - | - | - | - | effects=1; completed=4; DLQ=0; A/B |
| Build intern IntelliJ | - | - | - | - | SUCCESS la verificarea intermediara; ultima revalidare MCP a expirat cu diagnostic limitat |
| PowerShell parser, ambele scripturi | - | - | - | - | Fara erori sintactice |
| docker compose config --quiet | - | - | - | - | Exit 0; containerele nu au fost lansate |

Maven Shade raporteaza suprapuneri de manifest, LICENSE si module-info intre dependente.
JAR-ul rezultat a fost executat efectiv cu JDK 22; nu au aparut clase lipsa.
Ultima comanda build_project prin MCP a raportat timeout si imposibilitatea colectarii
mesajelor de build din IDE; nu a furnizat o eroare Java concreta. Rezultatele Maven de
mai sus si rularea JAR-ului sunt validarile complete disponibile pentru starea finala.

## F. Many subscribers

12 subscribers, activeSubscriptions=12, Health serving, Publish accepted, routedCount=12,
completed=12, DLQ=0. Testul se termina in deadline-urile existente; nu s-a observat thread
starvation in aceste rulări. Nu este un benchmark nelimitat de capacitate. Cleanup verifica
faptul ca toate cele 12 thread-uri au iesit; @TempDir cleanup este activ.

## G. Failure scenarios si procese separate

Toate cele sapte categorii cerute sunt demonstrate: crash-before-final-ACK, NACK/retry/DLQ,
broker failover, producer confirmation loss, storage failure, broker restart, backup/restore.
Confirmation loss este injectata prin UNAVAILABLE dupa commit; storage failure prin trigger
SQLite. Aceste teste nu simuleaza fizic o defectiune de disc/power loss.

Demonstratie multiproces executata folosind modurile scriptului:
`broker-a`, `broker-b`, `topic`, `inventory-crash`, `inventory`, `audit`, `producer`,
`stats`, `backup`, `demo`. Inventory a iesit efectiv cu 17; repornirea a produs DEDUP_SKIP.
Broker-a al sesiunii de audit a fost identificat prin PID parinte si calea DB izolata,
apoi oprit. Producerul si consumerii au continuat prin broker-b: completed=6, DLQ=0.
Backup-ul a fost redeschis cu completed=6. Nicio ClassNotFoundException.
Procesele Java care nu apartineau demonstratiei nu au fost oprite.

Loguri locale, intentionat ignorate de Git:

- `data/audit-evidence-20261015/`: clean-test, many, failover, verify, package, demo si runtime logs;
- `data/audit-runtime-20261015-185848/`: stdout/stderr pe proces si baze de smoke test.

## H. Documentatie si livrabile

Exista README, DELIVERABLES, STRUCTURE_MAPPING, runbook, failure evidence, trei diagrame,
contracte, scripturi si exact un ADR: `docs/adr/ADR-001-grpc-protobuf-sqlite.md`.
URL-ul documentat coincide cu remote origin: https://github.com/Andriana20035/pad-proiect .
Nu a fost creat tag/release sau proiect nou. Fisierele generate nu au fost editate manual.
Runbook-ul include restore la cale noua, pasi pentru porturi diferite si explicatia
cursorului round-robin resetat la fiecare proces Producer nou.

## I. Git hygiene

Nu sunt urmarite target/, JAR-uri, clase, DB/WAL/SHM/journal, workspace.xml sau chei din
setul de fisiere verificat. Cautarea de pattern-uri uzuale nu a gasit secrete in arborele
de lucru inspectat; nu este un audit exhaustiv al intregului istoric Git.
git diff --check a trecut. Exista modificari necomise si fisiere noi untracked, inclusiv
SubscriptionNotifier si testul cu 12 subscribers: trebuie incluse la predare.
Tag-ul lab2-v1.0 lipseste intentionat. Istoricul public/continutul remote nu a fost modificat.

## J. Intrebari de prezentare

1. **Ce aduce gRPC?** Contract tipizat, stub-uri generate, erori/deadline-uri si streaming.
2. **Ce costa?** Codegen/dependente, trafic binar mai greu de inspectat, integrare browser/proxy suplimentara.
3. **Ce protocol folosim?** gRPC Java peste HTTP/2; configuratia locala este plaintext.
4. **De ce HTTP/2?** Multiplexare de stream-uri si framing binar pe o conexiune.
5. **Diferenta fata de HTTP/1.1?** HTTP/2 multiplexeaza stream-uri; HTTP/1.1 obisnuit nu ofera aceeasi multiplexare pe aceeasi conexiune.
6. **Tipul Subscribe?** Server-streaming: o cerere, mai multe Delivery.
7. **Tipul Unsubscribe?** Unary, ca Publish/Ack/Nack/Health/CreateTopic.
8. **Cele patru tipuri gRPC?** Unary, server-streaming, client-streaming, bidirectional; aici sunt folosite primele doua.
9. **ACK RECEIVED?** Consumerul declara primirea; livrarea nu este finalizata.
10. **ACK PROCESSED?** Handlerul s-a terminat; inca nu finalizeaza livrarea.
11. **ACK PERSISTED?** Consumerul declara rezultat durabil; brokerul marcheaza COMPLETED.
12. **De ce nu blocheaza 12 stream-uri Health?** Workerii Subscribe au alt executor decat callback-urile RPC.
13. **Este complet non-blocking?** Nu: exista cate un worker elastic pentru fiecare stream activ.
14. **Cum asteapta Subscribe?** Condition in SubscriptionNotifier, cu fallback crossNodeRefreshMs si momentele lease/retry.
15. **Load balancing versus failover?** Primul distribuie apelurile sanatoase; al doilea incearca un alt endpoint dupa eroare.
16. **Cum demonstram round-robin?** Doua apeluri din acelasi ProducerClient; DemoMain si testul de load balancing.
17. **Ce face inversarea endpoint-urilor?** Schimba primul nod incercat; fiecare proces nou incepe cu un cursor nou.
18. **Ce face un port gresit?** Eroare de conectare/deadline si retry pe urmatorul endpoint; nu reconfigureaza serverul.
19. **Ce se intampla daca broker-a cade?** Stream-ul se inchide, ConsumerClient se reconecteaza la B; starea vine din DB comuna.
20. **Publish pe topic inexistent?** NOT_FOUND; TopicAdminMain trebuie sa creeze explicit topicul.
21. **Cum este verificat abonamentul?** SQL pe consumerId/topic/active; ACK/NACK verifica si owner-ul deliveryId.
22. **Consumer cade inainte de ACK final?** La urmatorul claim, lease expirat -> backoff -> aceeasi livrare, attempt incrementat.
23. **De ce pot aparea duplicate?** Efectul poate fi salvat, iar ACK-ul pierdut; brokerul nu poate sti ca efectul a reusit.
24. **Cum se evita efectul dublu?** processed_messages si efectul sunt persistate atomic in SQLite locala a consumerului.
25. **NACK repetat?** Retry exponential plafonat; dupa esecul attempt 3 implicit -> DLQ; NACK permanent -> DLQ imediat.
26. **Accepted inseamna procesat?** Nu: accepted confirma commit broker; procesarea se confirma prin PERSISTED/COMPLETED.
27. **At-least-once versus at-most-once?** Relivrare posibila versus risc de pierdere fara relivrare; aici retry-ul este limitat cu DLQ.
28. **Ordering exact?** O livrare in-flight per consumer/topic, minimul ID dintre mesajele scadente; backoff permite depasire.
29. **Ce se intampla la un milion de mesaje?** Nu exista benchmark; single writer, fsync, contention si scanari pot creste latenta. Productia poate cere sharding, broker dedicat sau alta baza.
30. **Cum facem backup/restore?** VACUUM INTO la alta cale, apoi pornire pe backup/copie valida, cu brokerii opriti la schimbarea DB.
