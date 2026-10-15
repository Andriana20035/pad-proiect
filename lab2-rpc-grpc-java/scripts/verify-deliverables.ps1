$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Required = @(
    "pom.xml",
    "README.md",
    "STRUCTURE_MAPPING.md",
    ".gitignore",
    "contracts/messaging-rpc-v1.proto",
    "src/main/proto/messaging_rpc.proto",
    "src/main/java/md/lab2/server/SubscriptionNotifier.java",
    "src/test/java/md/lab2/integration/ManySubscribersDoNotStarveUnaryRpcTest.java",
    "docs/adr/ADR-001-grpc-protobuf-sqlite.md",
    "docs/diagrams/architecture.mmd",
    "docs/diagrams/sequence-normal.mmd",
    "docs/diagrams/sequence-failure.mmd",
    "docs/evidence/failure-scenarios.md",
    "docs/runbook/README.md",
    "scripts/run-lab2.ps1",
    "DELIVERABLES.md"
)

foreach ($Path in $Required) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing deliverable: $Path"
    }
}

$AdrFiles = @(Get-ChildItem -LiteralPath "docs/adr" -Filter "ADR-*.md" -File)
if ($AdrFiles.Count -ne 1 -or $AdrFiles[0].Name -ne "ADR-001-grpc-protobuf-sqlite.md") {
    throw "The principal ADR must be ADR-001-grpc-protobuf-sqlite.md only"
}

$SourceProto = Get-Content "src/main/proto/messaging_rpc.proto" -Raw -Encoding UTF8
$ContractProto = Get-Content "contracts/messaging-rpc-v1.proto" -Raw -Encoding UTF8
if ($SourceProto -ne $ContractProto) {
    throw "The two proto contract copies are not identical"
}

mvn clean test
if ($LASTEXITCODE -ne 0) {
    throw "Tests failed"
}

Write-Host "All Lab 2 deliverables and tests are OK." -ForegroundColor Green
