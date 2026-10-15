param(
    [ValidateSet("tests", "package", "demo", "broker-a", "broker-b", "inventory", "inventory-crash", "audit", "topic", "producer", "backup", "stats")]
    [string]$Mode = "demo",
    [string]$OrderId = "ORD-3001"
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

mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$Jar = Join-Path $ProjectRoot "target\lab2-rpc-grpc-1.0.0-all.jar"
$Endpoints = "localhost:50051,localhost:50052"

switch ($Mode) {
    "demo" {
        New-Item -ItemType Directory -Force "data" | Out-Null
        & java -cp $Jar md.lab2.demo.DemoMain 2>&1 | Tee-Object -FilePath "data/demo.log"
    }
    "broker-a" {
        & java "-Dlab2.port=50051" "-Dlab2.node=broker-a" "-Dlab2.db=data/lab2-broker.db" -cp $Jar md.lab2.server.BrokerMain
    }
    "broker-b" {
        & java "-Dlab2.port=50052" "-Dlab2.node=broker-b" "-Dlab2.db=data/lab2-broker.db" -cp $Jar md.lab2.server.BrokerMain
    }
    "inventory" {
        & java "-Dlab2.endpoints=$Endpoints" -cp $Jar md.lab2.demo.InventoryConsumerMain
    }
    "inventory-crash" {
        Remove-Item "data\inventory-hard-crash.marker" -ErrorAction SilentlyContinue
        & java "-Dlab2.hardCrashOnce=true" "-Dlab2.endpoints=$Endpoints" -cp $Jar md.lab2.demo.InventoryConsumerMain
    }
    "audit" {
        & java "-Dlab2.endpoints=$Endpoints" -cp $Jar md.lab2.demo.AuditConsumerMain
    }
    "topic" {
        & java "-Dlab2.endpoints=$Endpoints" -cp $Jar md.lab2.demo.TopicAdminMain orders.v1
    }
    "producer" {
        & java "-Dlab2.endpoints=$Endpoints" -cp $Jar md.lab2.demo.OrderProducerMain $OrderId
    }
    "backup" {
        New-Item -ItemType Directory -Force "data\backups" | Out-Null
        & java "-Dlab2.db=data/lab2-broker.db" -cp $Jar md.lab2.tools.StoreMaintenanceMain backup "data/backups/lab2-backup.db"
    }
    "stats" {
        & java "-Dlab2.db=data/lab2-broker.db" -cp $Jar md.lab2.tools.StoreMaintenanceMain stats
    }
}
