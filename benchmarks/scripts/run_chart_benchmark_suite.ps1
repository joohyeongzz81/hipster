param(
    [string]$BaseUrl = "http://localhost:18080",
    [int]$WarmupCount = 2,
    [int]$MeasureCount = 5,
    [string[]]$ScenarioNames = @(),
    [string[]]$Modes = @(
        "CH1_JOIN_BASELINE",
        "CH2_DENORM_READ_MODEL",
        "CH3_QUERY_REWRITE_INDEXED",
        "CH4_REDIS_CACHE",
        "CH5_ELASTICSEARCH_READ"
    ),
    [string]$OutputRoot = "benchmarks/runs",
    [int]$RequestTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-BenchmarkStep {
    param(
        [string]$FilePath,
        [string[]]$Arguments = @()
    )

    & powershell -ExecutionPolicy Bypass -File $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Benchmark step failed: $FilePath (exit=$LASTEXITCODE)"
    }
}

$timestamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$runDir = Join-Path $OutputRoot "run_$timestamp"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

Invoke-BenchmarkStep -FilePath .\benchmarks\scripts\ensure_chart_benchmark_es_index.ps1

Invoke-BenchmarkStep -FilePath .\benchmarks\scripts\collect_mysql_global_status.ps1 -Arguments @(
    "-RunDir", $runDir,
    "-Phase", "before"
)

Invoke-BenchmarkStep -FilePath .\benchmarks\scripts\run_chart_benchmark.ps1 -Arguments @(
    "-BaseUrl", $BaseUrl,
    "-WarmupCount", "$WarmupCount",
    "-MeasureCount", "$MeasureCount",
    "-ScenarioNames", ($ScenarioNames -join ","),
    "-Modes", ($Modes -join ","),
    "-RunDir", $runDir,
    "-RequestTimeoutSeconds", "$RequestTimeoutSeconds",
    "-CaptureMysqlStatusPerCase"
)

Invoke-BenchmarkStep -FilePath .\benchmarks\scripts\collect_chart_explain.ps1 -Arguments @(
    "-BaseUrl", $BaseUrl,
    "-RunDir", $runDir,
    "-ScenarioNames", ($ScenarioNames -join ","),
    "-Modes", ($Modes -join ","),
    "-RequestTimeoutSeconds", "$RequestTimeoutSeconds"
)

Invoke-BenchmarkStep -FilePath .\benchmarks\scripts\collect_mysql_global_status.ps1 -Arguments @(
    "-RunDir", $runDir,
    "-Phase", "after"
)

Invoke-BenchmarkStep -FilePath .\benchmarks\scripts\collect_app_benchmark_logs.ps1 -Arguments @(
    "-RunDir", $runDir
)

Write-Host "Benchmark suite completed:"
Write-Host "  RunDir: $runDir"
