param(
    [string]$RunDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunDir)) {
    throw "RunDir is required."
}

$outputPath = Join-Path $RunDir "application.log"
docker compose -f docker-compose.yml -f docker-compose.benchmark.yml logs --timestamps app-benchmark | Set-Content -Path $outputPath -Encoding UTF8

Write-Host "Application logs saved:"
Write-Host "  Output: $outputPath"
