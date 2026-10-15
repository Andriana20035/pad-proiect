# Mapping cerinte Lab 2 -> implementare

| Cerinta | Implementare | Test / dovada |
|---|---|---|
| RPC inter-servicii | gRPC Java + `MessagingService` | `GrpcPublishSubscribeTest` |
| Format serializare | Protocol Buffers | `messaging_rpc.proto`, `ProtoContractTest` |
| Functii remote | CreateTopic, Publish, Subscribe, Unsubscribe, Ack, Nack, Stats, DLQ, Health | `ProtoContractTest` |
| HTTP/2 | transportul gRPC Java | `BrokerServer`, `HealthReply.transport` |
| Cereri concurente | executor RPC separat + executor elastic pentru Subscribe + notificare fara sleep + SQLite WAL | `LoadBalancingConcurrencyTest`, `ManySubscribersDoNotStarveUnaryRpcTest` |
| Persistenta | `SqliteMessageStore` | restart/backup tests |
| Rutare | topic + endpoint pool | `TopicRoutingTest` |
| Load balancing | round-robin client-side | `RoundRobinEndpointPoolTest`, `LoadBalancingConcurrencyTest` |
| Timeout/deadline | deadline pe unary; fara deadline scurt pe Subscribe | `ConsumerClient`, `ProducerClient` |
| Retry | lease + exponential backoff | `NackBackoffDlqTest`, `CrashBeforeFinalAckTest` |
| DLQ | tabela `dlq` | `NackBackoffDlqTest` |
| Deduplicare | `ConsumerLocalStore.processed_messages` | `CrashBeforeFinalAckTest` |
| Reconnect/failover | ConsumerClient roteste endpoint-urile | demo + failure docs |
| Topic dinamic | CreateTopic/Subscribe/Unsubscribe | `TopicRoutingTest`, `UnsubscribeIsolationTest` |
| ACK primit/procesat/persistat | enum `AckStage` | `ProtoContractTest` |
| Log corelat | `CorrelatedLog` | console/demo |
| Concurenta produceri + consumatori | doi ProducerClient, Inventory/Audit, brokeri cu notificatoare independente | `ConcurrentProcessingTest` |
| Izolare abonamente si owner | pereche consumer/topic, validare ACK/NACK | `SubscriptionIsolationTest` |
| Ordering declarat | o livrare in-flight, deliveryId minim dintre cele scadente; fara FIFO peste retry | `DeliverySemanticsTest`, README |
| Eroare SQLite prin RPC | Publish esueaza INTERNAL fara commit partial | `StorageFailureTest` |
| Restart serviciu gRPC | server nou pe aceeasi baza, stream nou, relivrare | `BrokerRpcRestartTest` |
| Pierdere raspuns ACK | reconnect, fara NACK artificial pentru efect reusit | `ConsumerAckTransportFailureTest` |
| Siguranta backup | destinatie distincta de baza live | `BackupSafetyTest` |
| Lifecycle client | pool inchis nu redeschide canale | `GrpcChannelPoolLifecycleTest` |
| Packaging executabil | manifest DemoMain, servicii gRPC/SQLite in shaded JAR | `java -jar target/lab2-rpc-grpc-1.0.0-all.jar` |
