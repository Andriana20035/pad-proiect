# ADR-001 — livrare, identitate și efecte persistente

## Problema
Un consumator poate salva efectul și cădea înainte de ACK. Brokerul nu poate distinge
acest caz de neprocesare. Memoria volatilă nu păstrează deduplicarea după restart.

## Alternative
At-most-once fără retry este simplu, dar poate pierde efectul.
Retry cu deduplicare doar în RAM nu protejează restart-ul.
RabbitMQ și o bază de date oferă infrastructură mai matură, dar depășesc brokerul
didactic pe socket-uri din acest laborator.

## Decizie și ipoteze
Broker TCP didactic, retry limitat și snapshot JSON persistent. Numărul comenzii
identifică solicitarea; producerul derivă un messageId și un correlationId stabil.
Datele și destinația devin imuabile la prima acceptare. Identitatea este propagată
în DELIVER și în toate logurile fluxului. ACK/NACK referă același messageId.
Consumatorul salvează efectul și deduplicarea ca o singură intrare atomică.
PUBLISHED apare numai după commit; ACK numai după commit-ul local.
Presupunem un broker per director, disc local disponibil, operații locale idempotente.

## Consecințe
Mesajul neconfirmat poate fi retrimis inclusiv după restart. Același efect local nu se
repetă. Timeout/deconectare și NACK retryable=true permit retry; erorile permanente
merg direct în DLQ. Maximum 3 încercări implicit, aceeași configurație pentru demo și broker.
Înregistrarea originală a solicitării împiedică reintroducerea după ACK sau DLQ.
Un mesaj invalid este respins înainte de coadă, nu retrimis fără limită.

## Limite
Este o politică at-least-once cu retry limitat, nu o promisiune de procesare universală.
At-most-once ar confirma/elimina înainte de efect, riscând pierderea acestuia.
Nu pretindem exactly-once pentru servicii externe sau pentru pierderea discului.
Snapshot-urile complete și istoricul fără retenție sunt potrivite volumului didactic.
Pierderea alimentării și defectarea discului depășesc garanția restart-ului de proces.
