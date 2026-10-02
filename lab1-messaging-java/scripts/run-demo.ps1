param(
    [ValidateSet('demo', 'broker', 'inventory', 'audit', 'producer', 'tests')]
    [string]$Mode = 'demo',
    [switch]$Crash,
    [string]$Config,
    [string]$MavenRepository
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location -LiteralPath $projectRoot
try {
    if (-not $Config) { $Config = Join-Path $projectRoot 'deployment/application.properties' }
    $Config = (Resolve-Path -LiteralPath $Config).Path
    $mavenOptions = @()
    if (-not $MavenRepository) { $MavenRepository = Join-Path $env:USERPROFILE '.m2/repository' }
    if ($MavenRepository) { $mavenOptions += "-Dmaven.repo.local=$MavenRepository" }
    if ($Mode -eq 'tests') {
        & mvn '-B' @mavenOptions test
        if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
        return
    }
    & mvn '-B' @mavenOptions '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    $mainClasses = @{
        demo = 'md.lab.DemoMain'
        broker = 'md.lab.integrationservice.BrokerMain'
        inventory = 'md.lab.consumers.InventoryConsumer'
        audit = 'md.lab.consumers.AuditConsumer'
        producer = 'md.lab.orderservice.OrderProducer'
    }
    $javaOptions = @("-Dapp.config=$Config", '-cp', 'target/classes;target/dependency/*', $mainClasses[$Mode])
    if ($Crash -and $Mode -eq 'inventory') { $javaOptions += '--crash-process' }
    $javaExecutable = 'java'
    if ($env:JAVA_HOME) { $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe' }
    if ($Mode -eq 'demo') {
        New-Item -ItemType Directory -Path 'data' -Force | Out-Null
        & $javaExecutable @javaOptions | Tee-Object -FilePath 'data/demo.log'
    } else {
        & $javaExecutable @javaOptions
    }
    if ($LASTEXITCODE -ne 0) { throw "Process exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
