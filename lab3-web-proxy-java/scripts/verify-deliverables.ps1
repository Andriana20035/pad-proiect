$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
mvn clean test
if ($LASTEXITCODE -ne 0) { throw 'Testele JUnit au eșuat' }
mvn package
if ($LASTEXITCODE -ne 0) { throw 'Build Maven a eșuat' }
if (Test-Path 'target/lab3-processes.json') { throw 'Oprește întâi procesele vechi: .\scripts\run-lab3.ps1 stop' }
$python = if (Get-Command py -ErrorAction SilentlyContinue) { 'py' } else { 'python' }
try {
    & powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 start
    if ($LASTEXITCODE -ne 0) { throw 'Nu s-a putut porni laboratorul' }
    & $python .\scripts\verify.py
    if ($LASTEXITCODE -ne 0) { throw 'Verificarea HTTP a eșuat' }
    & $python .\scripts\benchmark.py --requests 80
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark-ul a eșuat' }
} finally {
    & powershell -ExecutionPolicy Bypass -File .\scripts\run-lab3.ps1 stop
}
& $python .\scripts\process_failure.py
if ($LASTEXITCODE -ne 0) { throw 'Testul de cădere a procesului a eșuat' }
Write-Host 'ALL LAB3 CHECKS PASSED'
