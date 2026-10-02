# ADR-003 — protocol, validare și deadline

## Problema
Mesajele invalide și conexiunile care nu răspund nu trebuie să consume retry-uri nelimitate.
Valorile de rețea și stocare trebuie schimbate fără modificarea codului.

## Alternative
XML este posibil, dar JSON și Jackson sunt suficiente pentru acest contract.
Interpretarea dinamică JSON Schema adaugă o dependență; verificarea explicită v1
este mai mică pentru un contract fix. Timeout numai la citire nu acoperă conectarea/scrierea.

## Decizie și ipoteze
TCP cu JSON delimitat prin newline. WireMessage definește comenzile; MessageEnvelope
definește datele. Validator explicit pentru v1, câmpuri obligatorii, UUID-uri,
timestamp și payload cu produs/cantitate. Jackson respinge câmpurile externe necunoscute.
Producerul nu face retry automat: utilizatorul poate retrimite aceeași solicitare,
iar brokerul aplică idempotenta. Configurația este externă în properties, fără secrete.
Deadline-ul producerului acoperă conectarea și schimbul de mesaje; un watchdog
închide socket-ul la expirare. Brokerul are timeout la mesajul inițial și deadline ACK.

## Consecințe
Contract invalid → NACK fără publicare. Eroare permanentă la consumator → DLQ.
I/O local tranzient → NACK retryable=true. Retry-ul efectului este permis deoarece
efectul și deduplicarea sunt atomice. Subscription confirmată poate aștepta lucru nelimitat.

## Limite
Validatorul și schema documentată trebuie actualizate împreună.
Deadline-ul de transport nu anulează un efect deja salvat, motiv pentru care avem deduplicare.
Brokerul nu este replicat și nu autentifică clienții; laboratorul rulează într-un mediu controlat.
