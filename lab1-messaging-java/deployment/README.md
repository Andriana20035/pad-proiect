# Deployment

Rularea principală folosește JDK 17+, Maven și scripts/run-demo.ps1.
Configurația publică este application.properties; o configurație locală poate fi
selectată prin -Dapp.config=/cale/application.properties. Secretele viitoare trebuie
furnizate extern, nu salvate în repository.

Alternativ, dacă Docker este instalat:

```sh
docker compose up --build -d broker inventory audit
docker compose run --rm producer
docker compose logs -f broker inventory audit
docker compose down
```

Volumele păstrează datele după down. Nu folosi down -v dacă dorești să păstrezi datele.
Brokerul ascultă pe 5050; consumatorii se reconectează dacă pornește mai târziu.
Configurația JVM din compose schimbă host-ul în broker și directorul în /data.
Pentru crash real poți porni serviciul Inventory cu argumentul --crash-process,
apoi să îl repornești fără acest argument.
Nu rula brokerul local și cel Docker pe același port.
