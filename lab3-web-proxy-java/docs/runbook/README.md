# Runbook – laboratorul 3

Pentru demonstrația completă în Windows / VS Code, vezi [GHID_DEMONSTRATIE.md](../GHID_DEMONSTRATIE.md).
Pentru explicația fiecărei funcționalități, vezi [CE_AM_IMPLEMENTAT.md](../CE_AM_IMPLEMENTAT.md).

## Comenzi rapide

```powershell
mvn clean test
mvn package
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 start
py .\scripts\verify.py
py .\scripts\benchmark.py --requests 80
powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 stop
py .\scripts\process_failure.py
```

`process_failure.py` trebuie pornit numai după oprirea tuturor proceselor laboratorului, pentru că își creează singur cele patru procese.
