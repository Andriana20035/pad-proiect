param(
    [ValidateSet("tests", "package", "demo", "broker-a", "broker-b", "inventory", "inventory-crash", "audit", "topic", "producer", "backup", "stats")]
    [string]$Mode = "demo",
    [string]$OrderId = "ORD-3001",
    [string]$Endpoints = "localhost:50051,localhost:50052",
    [string]$DataDirectory = "data",
    [ValidateRange(1, 65535)]
    [int]$PortA = 50051,
    [ValidateRange(1, 65535)]
    [int]$PortB = 50052
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

if ($Mode -eq "tests") {
    mvn clean test
    exit $LASTEXITCODE
}

if ($Mode -eq "package") {
    mvn -DskipTests package
    exit $LASTEXITCODE
}

$Jar = Join-Path $ProjectRoot "target\lab2-rpc-grpc-1.0.0-all.jar"
# Reuse the built JAR: running brokers may hold it open on Windows.
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
New-Item -ItemType Directory -Force $DataDirectory | Out-Null
$BrokerDb = Join-Path $DataDirectory "lab2-broker.db"
$InventoryDb = Join-Path $DataDirectory "inventory-rpc.db"
$AuditDb = Join-Path $DataDirectory "audit-rpc.db"
$CrashMarker = Join-Path $DataDirectory "inventory-hard-crash.marker"

switch ($Mode) {
    "demo" {
        & java -cp $Jar md.lab2.demo.DemoMain | Tee-Object -FilePath (Join-Path $DataDirectory "demo.log")
    }
    "broker-a" {
        & java "-Dlab2.port=$PortA" "-Dlab2.node=broker-a" "-Dlab2.db=$BrokerDb" -cp $Jar md.lab2.server.BrokerMain
    }
    "broker-b" {
        & java "-Dlab2.port=$PortB" "-Dlab2.node=broker-b" "-Dlab2.db=$BrokerDb" -cp $Jar md.lab2.server.BrokerMain
    }
    "inventory" {
        & java "-Dlab2.endpoints=$Endpoints" "-Dlab2.inventoryDb=$InventoryDb" -cp $Jar md.lab2.demo.InventoryConsumerMain
    }
    "inventory-crash" {
        Remove-Item -LiteralPath $CrashMarker -ErrorAction SilentlyContinue
        & java "-Dlab2.hardCrashOnce=true" "-Dlab2.endpoints=$Endpoints" "-Dlab2.inventoryDb=$InventoryDb" "-Dlab2.crashMarker=$CrashMarker" -cp $Jar md.lab2.demo.InventoryConsumerMain
    }
    "audit" {
        & java "-Dlab2.endpoints=$Endpoints" "-Dlab2.auditDb=$AuditDb" -cp $Jar md.lab2.demo.AuditConsumerMain
    }
    "topic" {
        & java "-Dlab2.endpoints=$Endpoints" -cp $Jar md.lab2.demo.TopicAdminMain orders.v1
    }
    "producer" {
        & java "-Dlab2.endpoints=$Endpoints" -cp $Jar md.lab2.demo.OrderProducerMain $OrderId
    }
    "backup" {
        $BackupDirectory = Join-Path $DataDirectory "backups"
        New-Item -ItemType Directory -Force $BackupDirectory | Out-Null
        & java "-Dlab2.db=$BrokerDb" -cp $Jar md.lab2.tools.StoreMaintenanceMain backup (Join-Path $BackupDirectory "lab2-backup.db")
    }
    "stats" {
        & java "-Dlab2.db=$BrokerDb" -cp $Jar md.lab2.tools.StoreMaintenanceMain stats
    }
}
exit $LASTEXITCODE
