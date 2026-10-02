# ADR-002 — canale, concurență și ordering

## Problema
Retry-ul și două conexiuni cu același ID pot permite unui mesaj ulterior să depășească unul eșuat.

## Alternative
Coada comună cu consumatori concurenți crește debitul, dar complică ordinea.
Ordinea globală între Inventory și Audit necesită coordonare suplimentară.
Un singur fir pentru întregul broker ar bloca ambii consumatori dacă unul este lent.

## Decizie și ipoteze
Un canal logic fix, orders, cu o coadă LinkedBlockingDeque per consumerId.
Publicările sunt serializate pentru a defini ordinea acceptării, conexiunile sunt
servite concurent, iar Inventory și Audit lucrează independent.
O singură conexiune activă și o singură livrare în curs per consumerId.
Retry-ul se inserează în față; livrarea recuperată după restart precedă coada existentă.

## Consecințe
FIFO per consumator/canal. Broadcast înseamnă o livrare separată pentru fiecare
destinatar; publicarea direcționată realizează comunicarea unul-la-unu.
Un mesaj eșuat blochează următoarele numai până la ACK sau mutarea în DLQ.

## Limite
Nu există ordering global între consumatori. Acceptarea unei publicări concurente
este ordonată de broker, nu de momentul apelului în clienți.
Coada și istoricul nu au încă retenție sau backpressure.
