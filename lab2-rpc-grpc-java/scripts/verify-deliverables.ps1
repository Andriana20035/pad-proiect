$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Required = @(
    "pom.xml",
    "README.md",
    "contracts/messaging-rpc-v1.proto",
    "src/main/proto/messaging_rpc.proto",
    "docs/adr/ADR-001-grpc-protobuf-sqlite.md",
    "docs/diagrams/architecture.mmd",
    "docs/diagrams/sequence-normal.mmd",
    "docs/diagrams/sequence-failure.mmd",
    "docs/evidence/failure-scenarios.md",
    "docs/runbook/README.md",
    "DELIVERABLES.md"
)

foreach ($Path in $Required) {
    if (-not (Test-Path $Path)) {
        throw "Missing deliverable: $Path"
    }
}

$SourceProto = Get-Content "src/main/proto/messaging_rpc.proto" -Raw
$ContractProto = Get-Content "contracts/messaging-rpc-v1.proto" -Raw
if ($SourceProto -ne $ContractProto) {
    throw "The two proto contract copies are not identical"
}

mvn clean test
if ($LASTEXITCODE -ne 0) {
    throw "Tests failed"
}

Write-Host "All Lab 2 deliverables and tests are OK." -ForegroundColor Green
