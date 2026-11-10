param([ValidateSet('start','stop','verify','benchmark','compile')][string]$Mode='start')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$pidFile = Join-Path $root 'target/lab3-processes.json'
$jar = Join-Path $root 'target/lab3-web-proxy-1.0.0.jar'

if ($Mode -eq 'compile' -or $Mode -eq 'start') {
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Compilarea Maven a eșuat.' }
}
if ($Mode -eq 'stop') {
    if (!(Test-Path $pidFile)) { Write-Host 'Nu există procese înregistrate de script.'; exit 0 }
    $entries = Get-Content $pidFile -Raw | ConvertFrom-Json
    foreach ($entry in $entries) {
        $proc = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if ($proc -and $proc.ProcessName -match '^java(w)?$') {
            Stop-Process -Id $entry.pid -Force -ErrorAction SilentlyContinue
            Write-Host "Oprit: $($entry.name) (PID $($entry.pid))"
        }
    }
    Remove-Item $pidFile -Force
    exit 0
}
if ($Mode -eq 'start') {
    if (Test-Path $pidFile) { throw 'Există deja o sesiune. Rulează mai întâi: .\scripts\run-lab3.ps1 stop' }
    $entries = @()
    $nodes = @(
        @{ name='storage'; args=@('storage') },
        @{ name='dw-a'; args=@('backend','9011','dw-a') },
        @{ name='dw-b'; args=@('backend','9012','dw-b') },
        @{ name='proxy'; args=@('proxy') }
    )
    foreach ($node in $nodes) {
        $out = Join-Path $root ("target/" + $node.name + '.out.log')
        $err = Join-Path $root ("target/" + $node.name + '.err.log')
        $arg = @('-jar', ('"' + $jar + '"')) + $node.args
        $proc = Start-Process -FilePath 'java' -ArgumentList $arg -WorkingDirectory $root `
                -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
        $entries += @{ name=$node.name; pid=$proc.Id }
        Write-Host "Pornit $($node.name): PID $($proc.Id), log: $out"
        Start-Sleep -Milliseconds 650
        if ($proc.HasExited) { throw "Procesul $($node.name) s-a oprit. Verifică $err" }
    }
    $entries | ConvertTo-Json | Set-Content $pidFile -Encoding UTF8
    Write-Host 'Deschide: http://127.0.0.1:18080/api/orders/ORD-2001'
    Write-Host 'Metrici: http://127.0.0.1:18080/metrics'
    Write-Host 'Oprire: .\scripts\run-lab3.ps1 stop'
    exit 0
}
$python = if (Get-Command py -ErrorAction SilentlyContinue) { 'py' } else { 'python' }
if ($Mode -eq 'verify') { & $python scripts/verify.py }
if ($Mode -eq 'benchmark') { & $python scripts/benchmark.py --requests 80 }
if ($LASTEXITCODE -ne 0) { throw 'Scriptul de verificare a eșuat.' }
