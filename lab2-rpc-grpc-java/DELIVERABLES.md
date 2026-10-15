# Livrabile obligatorii - Lab 2

1. **URL repository**: `https://github.com/Andriana20035/pad-proiect`
2. **Tag evaluat**: `lab2-v1.0` (se creeaza numai dupa verificarea finala)
3. **Cod sursa**: `lab2-rpc-grpc-java/src/main/java`
4. **Contracte**: `src/main/proto/messaging_rpc.proto` si `contracts/messaging-rpc-v1.proto`
5. **Teste automate**: `src/test/java/md/lab2/{unit,contract,integration,failure}`
6. **Diagrame**: `docs/diagrams/`
7. **ADR**: `docs/adr/ADR-001-grpc-protobuf-sqlite.md`
8. **Dovezi scenarii de esec**: `docs/evidence/failure-scenarios.md` + testele failure
9. **Instructiuni reproducere**: `docs/runbook/README.md` si `scripts/run-lab2.ps1`
10. **Garantii si limite**: README + ADR + `docs/LIMITATIONS-AND-SCALING.md`

Inainte de tag:

```powershell
mvn clean test
mvn -DskipTests package
git status
```

Tag-ul nu se muta dupa evaluare. Pentru corectii se foloseste `lab2-v1.1`.
