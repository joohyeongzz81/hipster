param(
    [switch]$Build
)

$ErrorActionPreference = "Stop"

$buildFlag = if ($Build) { "--build" } else { "" }
$command = "docker compose -f docker-compose.yml -f docker-compose.benchmark.yml up -d $buildFlag app-benchmark"
Invoke-Expression $command

Write-Host "Started benchmark app stack."
Write-Host "Benchmark app base URL: http://localhost:18080"
