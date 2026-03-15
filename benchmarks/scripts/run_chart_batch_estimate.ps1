param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$SampleChunkCount = 2,
    [int]$ChunkSize = 2000,
    [string]$WriteMode = "UPSERT",
    [int]$EsSamplePageCount = 2,
    [int]$EsBatchSize = 2000,
    [string]$OutputRoot = "benchmarks/runs",
    [string]$RunDir = "",
    [int]$RequestTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-SafeName {
    param([string]$Value)
    return ($Value -replace '[^A-Za-z0-9_\-]', '_')
}

$timestamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
if ([string]::IsNullOrWhiteSpace($RunDir)) {
    $runDir = Join-Path $OutputRoot "estimate_$timestamp"
}
else {
    $runDir = $RunDir
}

New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$meta = [ordered]@{
    startedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    sampleChunkCount = $SampleChunkCount
    chunkSize = $ChunkSize
    writeMode = $WriteMode
    esSamplePageCount = $EsSamplePageCount
    esBatchSize = $EsBatchSize
    requestTimeoutSeconds = $RequestTimeoutSeconds
}
$meta | ConvertTo-Json -Depth 5 | Set-Content -Path (Join-Path $runDir "meta.json") -Encoding UTF8

$query = [ordered]@{
    sampleChunkCount = $SampleChunkCount
    chunkSize = $ChunkSize
    writeMode = $WriteMode
    esSamplePageCount = $EsSamplePageCount
    esBatchSize = $EsBatchSize
}

$queryString = ($query.GetEnumerator() | ForEach-Object {
    "{0}={1}" -f [System.Uri]::EscapeDataString($_.Key), [System.Uri]::EscapeDataString([string]$_.Value)
}) -join "&"

$url = "$BaseUrl/internal/benchmarks/chart-batch/estimate?$queryString"

Write-Host "Running chart batch estimate..."
Write-Host "  URL: $url"
Write-Host "  Output: $runDir"
Write-Host "  Projection samples: $SampleChunkCount chunks per segment"
Write-Host "  ES samples: $EsSamplePageCount pages per segment"

$watch = [System.Diagnostics.Stopwatch]::StartNew()
$response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $RequestTimeoutSeconds
$watch.Stop()

$body = $response.Content | ConvertFrom-Json
$body | ConvertTo-Json -Depth 20 | Set-Content -Path (Join-Path $runDir "estimate_raw.json") -Encoding UTF8

$data = $body.data
$totalEstimatedMinutes = 0
if ($null -ne $data.projectionEstimatedMinutes) {
    $totalEstimatedMinutes += [double]$data.projectionEstimatedMinutes
}
if ($null -ne $data.esEstimatedMinutes) {
    $totalEstimatedMinutes += [double]$data.esEstimatedMinutes
}
if ($null -ne $data.publishEstimatedMinutes) {
    $totalEstimatedMinutes += [double]$data.publishEstimatedMinutes
}

$summary = [ordered]@{
    requestedAt = (Get-Date).ToString("o")
    wallMillis = [Math]::Round($watch.Elapsed.TotalMilliseconds, 2)
    projectionEstimatedMinutes = $data.projectionEstimatedMinutes
    esEstimatedMinutes = $data.esEstimatedMinutes
    publishEstimatedMinutes = $data.publishEstimatedMinutes
    totalEstimatedMinutes = [Math]::Round($totalEstimatedMinutes, 6)
    projectionStartChunks = @($data.projectionStartChunks)
    esStartPages = @($data.esStartPages)
}
$summary | ConvertTo-Json -Depth 10 | Set-Content -Path (Join-Path $runDir "estimate_summary.json") -Encoding UTF8

Write-Host "Estimate completed:"
Write-Host ("  wallMillis={0}" -f $summary.wallMillis)
Write-Host ("  projectionEstimatedMinutes={0}" -f $summary.projectionEstimatedMinutes)
Write-Host ("  esEstimatedMinutes={0}" -f $summary.esEstimatedMinutes)
Write-Host ("  publishEstimatedMinutes={0}" -f $summary.publishEstimatedMinutes)
Write-Host ("  totalEstimatedMinutes={0}" -f $summary.totalEstimatedMinutes)
Write-Host "Files:"
Write-Host ("  {0}" -f (Join-Path $runDir "meta.json"))
Write-Host ("  {0}" -f (Join-Path $runDir "estimate_raw.json"))
Write-Host ("  {0}" -f (Join-Path $runDir "estimate_summary.json"))
