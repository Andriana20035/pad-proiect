# Cum încărcăm laboratorul 3 în GitHub și creăm tagul

Repository: https://github.com/Andriana20035/pad-proiect

Laboratorul 3 rămâne într-un folder separat: `lab3-web-proxy-java`. Nu este necesară modificarea laboratoarelor 1 și 2.

## A. Pregătirea proiectului

1. Testează mai întâi laboratorul conform `GHID_DEMONSTRATIE.md`.
2. Descarcă sau păstrează folderul corectat `lab3-web-proxy-java`.
3. În VS Code, deschide un terminal PowerShell într-un folder în care vrei să clonezi repository-ul.

Dacă nu ai deja o copie Git locală:

```powershell
git clone https://github.com/Andriana20035/pad-proiect.git
cd pad-proiect
```

Dacă ai deja repository-ul clonat, deschide-l cu **File → Open Folder** și verifică:

```powershell
git remote -v
git status
git branch -a
```

Trebuie să vezi adresa repository-ului vostru la `origin`. Dacă folderul nu este o copie Git, comanda `git status` va afișa `not a git repository`; atunci folosește `git clone`.

## B. Copierea laboratorului în repository

1. În Explorer, deschide folderul proiectului `pad-proiect`.
2. Copiază **tot folderul** `lab3-web-proxy-java` în rădăcina lui, lângă laboratoarele 1 și 2.
3. Nu copia `target/`, `data/`, `.class`, `.jar` sau logurile. `.gitignore` le exclude deja.
4. Asigură-te că noul folder conține `pom.xml`, `src/`, `scripts/`, `contracts/`, `docs/`.

Structura finală:

```text
pad-proiect/
  lab1-messaging-java/
  lab2-rpc-grpc-java/
  lab3-web-proxy-java/
```

## C. Recomandat: branch separat și Pull Request

Pentru că repository-ul este comun, e mai sigur să încarci într-un branch și să faci Pull Request, în loc să modifici direct `main`.

Din rădăcina `pad-proiect`:

```powershell
git switch main
git pull --ff-only origin main
git switch -c lab3-web-proxy
git add lab3-web-proxy-java
git status
git commit -m "Add lab 3 web proxy and resilience"
git push -u origin lab3-web-proxy
```

Dacă ramura principală nu se numește `main`, înlocuiește `main` cu numele afișat de `git branch -a` / pagina GitHub. Dacă `lab3-web-proxy` există deja, alege un alt nume, de exemplu `lab3-web-proxy-v1`.

Apoi deschide:

https://github.com/Andriana20035/pad-proiect/pulls

Apasă **New pull request**, alege ramura de bază `main` și ramura nouă `lab3-web-proxy`, verifică să fie modificat doar noul folder și creează Pull Request. Dacă nu ai drept să faci merge, îl poate aproba proprietarul repository-ului.

## D. Tagul `lab3-v1.0`

**Tagul se creează după ce Pull Request-ul este inclus în ramura care va fi evaluată**, nu înainte. Verifică mai întâi ce taguri există:

```powershell
git fetch --tags
git tag --list "lab3-*"
```

Dacă `lab3-v1.0` nu există și modificările sunt deja în `main`:

```powershell
git switch main
git pull --ff-only origin main
git status
git tag -a lab3-v1.0 -m "Laboratorul 3 v1.0"
git push origin lab3-v1.0
```

Verifică pe:

- https://github.com/Andriana20035/pad-proiect/tags
- https://github.com/Andriana20035/pad-proiect/tree/lab3-v1.0

Tagul trebuie să indice commitul care conține fișierele finale ale laboratorului 3. Nu muta niciodată un tag folosit la evaluare.

## E. Dacă profesoara cere modificări

Poți schimba codul și documentația și după crearea tagului. Lucrează într-un branch nou, fă commit și Pull Request, apoi creează **un tag nou**, de exemplu `lab3-v1.1`, pentru versiunea corectată. Nu șterge și nu muta `lab3-v1.0`.

O variantă posibilă:

```powershell
git switch main
git pull --ff-only origin main
git switch -c lab3-fixes-v1.1
# modifici fișierele, testezi
mvn -f lab3-web-proxy-java/pom.xml clean test
git add lab3-web-proxy-java
git commit -m "Fix lab 3 after review"
git push -u origin lab3-fixes-v1.1
```

După aprobarea/integrarea modificărilor, pe `main` actualizat:

```powershell
git switch main
git pull --ff-only origin main
git tag -a lab3-v1.1 -m "Laboratorul 3 v1.1"
git push origin lab3-v1.1
```

**Important:** dacă modificările sunt încă pe branch și nu au fost incluse în `main`, nu crea tagul de pe `main` încă. În orice moment, verifică `git status` și `git log -1 --oneline` înainte de tag.
