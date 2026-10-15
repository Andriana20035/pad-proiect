# Deployment

Docker este optional. Pentru evaluarea de baza este mai simplu sa rulezi procesele din
IntelliJ/PowerShell.

Cele doua broker instances din `docker-compose.yml` folosesc acelasi volum SQLite doar ca
demonstratie locala. Pentru productie, un cluster real ar folosi o baza server/replicata sau
un storage distribuit, nu un fisier SQLite comun intre masini.
