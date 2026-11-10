# Pornire în procese separate

Rulează `storage`, `backend 9011 dw-a`, `backend 9012 dw-b` și `proxy` în procese Java diferite.
Fiecare DW trimite operațiile de citire/scriere către serviciul comun de sincronizare de pe portul 9020.
Serviciul serializează scrierile și salvează datele în `data/warehouse.properties`.
La restart, datele se încarcă din acest fișier. Nu porni două instanțe ale serviciului de stocare pe aceeași cale de fișier.

Pe Windows poți folosi `scripts/run-lab3.ps1 start` și `scripts/run-lab3.ps1 stop`.
Cu Docker: `docker compose up --build`, apoi `docker compose down`.
