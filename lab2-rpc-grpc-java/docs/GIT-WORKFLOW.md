# Workflow Git pentru cele 4 laboratoare si 3 colaboratori

Repository-ul existent este:

`https://github.com/Andriana20035/pad-proiect.git`

Tag-ul Lab 1 existent este `lab1-v1.0`. Nu il mutati si nu il recreati.

## Structura recomandata a repository-ului comun

```text
pad-proiect/
├── lab1-messaging-java/
├── lab2-rpc-grpc-java/
├── lab3-.../
└── lab4-.../
```

Fiecare laborator ramane intr-un folder separat. `.git` trebuie sa existe doar in radacina
`pad-proiect`, nu in fiecare laborator.

## Pentru Lab 2

Din radacina repository-ului:

```powershell
git status
git pull origin main
```

Extrage/copiaza folderul `lab2-rpc-grpc-java` LANGA `lab1-messaging-java`, nu in interiorul
Lab 1. Verifica:

```powershell
mvn -f .\lab2-rpc-grpc-java\pom.xml clean test
git status
```

Apoi:

```powershell
git checkout -b lab2-andriana
git add lab2-rpc-grpc-java
git commit -m "Lab 2 - inter-service communication with gRPC"
git push -u origin lab2-andriana
```

Pe GitHub creezi Pull Request `lab2-andriana -> main`. Dupa merge:

```powershell
git checkout main
git pull origin main
git tag -a lab2-v1.0 -m "Lab 2 version 1.0"
git push origin lab2-v1.0
```

Verifica pe GitHub ca tag-ul pointeaza la commit-ul evaluat.

## Pentru colege la Lab 3 si Lab 4

Fiecare colega incepe din `main` actualizat:

```powershell
git checkout main
git pull origin main
git checkout -b lab3-nume
```

sau `lab4-nume`. Adauga numai folderul laboratorului ei, ruleaza testele, commit, push si PR.
Dupa merge se creeaza tag-ul `lab3-v1.0` / `lab4-v1.0`.

## Regula importanta pentru corectii

Nu folositi `git tag -f` pe un tag evaluat. Daca Lab 2 necesita corectie dupa v1.0:

```powershell
git tag -a lab2-v1.1 -m "Lab 2 correction 1.1"
git push origin lab2-v1.1
```

## Evitarea conflictelor intre 3 persoane

- fiecare laborator = folder separat;
- fiecare persoana = branch separat;
- `git pull origin main` inainte de branch;
- nu editati simultan acelasi README din radacina fara coordonare;
- folositi Pull Request chiar daca repository-ul este mic;
- tag doar dupa merge si dupa `mvn clean test`.
