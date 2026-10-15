# Mapping cerinte Lab 2 -> implementare

| Cerinta | Implementare | Test / dovada |
|---|---|---|
| RPC inter-servicii | gRPC Java + `MessagingService` | `GrpcPublishSubscribeTest` |
| Format serializare | Protocol Buffers | `messaging_rpc.proto`, `ProtoContractTest` |
| Functii remote | CreateTopic, Publish, Subscribe, Unsubscribe, Ack, Nack, Stats, DLQ, Health | `ProtoContractTest` |
| HTTP/2 | transportul gRPC Java | `BrokerServer`, `HealthReply.transport` |
| Cereri concurente | executor server + canale gRPC thread-safe + SQLite WAL | `LoadBalancingConcurrencyTest` |
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
