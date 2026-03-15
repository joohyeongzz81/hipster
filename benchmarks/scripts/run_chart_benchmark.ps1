param(
    [string]$BaseUrl = "http://localhost:8080",
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
    [string]$RunDir = "",
    [int]$RequestTimeoutSeconds = 180,
    [switch]$CaptureMysqlStatusPerCase
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-BenchmarkHelperScript {
    param(
        [string]$FilePath,
        [string[]]$Arguments = @()
    )

    & powershell -ExecutionPolicy Bypass -File $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Benchmark helper step failed: $FilePath (exit=$LASTEXITCODE)"
    }
}

function Get-Median {
    param([double[]]$Values)
    if (-not $Values -or $Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $mid = [int]($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 0) {
        return (($sorted[$mid - 1] + $sorted[$mid]) / 2.0)
    }
    return $sorted[$mid]
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percentile
    )
    if (-not $Values -or $Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $rank = [Math]::Ceiling(($Percentile / 100.0) * $sorted.Count)
    $index = [Math]::Max([Math]::Min($rank - 1, $sorted.Count - 1), 0)
    return $sorted[$index]
}

function Invoke-BenchmarkRequest {
    param(
        [string]$Url,
        [int]$TimeoutSeconds
    )

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSeconds
    $watch.Stop()

    $body = $response.Content | ConvertFrom-Json
    return @{
        wallMillis = [Math]::Round($watch.Elapsed.TotalMilliseconds, 2)
        statusCode = [int]$response.StatusCode
        body = $body
    }
}

function Get-TimingValue {
    param(
        [object]$Timings,
        [string]$Name
    )

    if ($null -eq $Timings) {
        return $null
    }

    $property = $Timings.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }

    return [double]$property.Value
}

function Get-ScenarioFiles {
    Get-ChildItem -Path "src/main/resources/benchmarks/chart/scenarios" -Filter "*.json" |
        Sort-Object Name |
        ForEach-Object {
            $content = Get-Content -Path $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
            [PSCustomObject]@{
                name = $content.name
                path = $content.path
                page = $content.page
                size = $content.size
            }
        }
}

function Get-SafeName {
    param([string]$Value)
    return ($Value -replace '[^A-Za-z0-9_\-]', '_')
}

$Modes = @($Modes | ForEach-Object { $_ -split "," } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$ScenarioNames = @($ScenarioNames | ForEach-Object { $_ -split "," } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$timestamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
if ([string]::IsNullOrWhiteSpace($RunDir)) {
    $runDir = Join-Path $OutputRoot "run_$timestamp"
}
else {
    $runDir = $RunDir
}
$rawDir = Join-Path $runDir "raw_responses"
$mysqlStatusDir = Join-Path $runDir "mysql_status"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
New-Item -ItemType Directory -Force -Path $rawDir | Out-Null
if ($CaptureMysqlStatusPerCase) {
    New-Item -ItemType Directory -Force -Path $mysqlStatusDir | Out-Null
}

$meta = [ordered]@{
    startedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    warmupCount = $WarmupCount
    measureCount = $MeasureCount
    modes = $Modes
    scenarioNames = $ScenarioNames
    requestTimeoutSeconds = $RequestTimeoutSeconds
    scenarioSource = "src/main/resources/benchmarks/chart/scenarios/*.json"
}
$meta | ConvertTo-Json -Depth 5 | Set-Content -Path (Join-Path $runDir "meta.json") -Encoding UTF8

$results = @()
$failures = @()
$scenarios = Get-ScenarioFiles
if ($ScenarioNames.Count -gt 0) {
    $scenarios = $scenarios | Where-Object { $ScenarioNames -contains $_.name }
}
$scenarios | ConvertTo-Json -Depth 5 | Set-Content -Path (Join-Path $runDir "scenarios.json") -Encoding UTF8

foreach ($mode in $Modes) {
    $cacheStates = @($null)
    if ($mode -eq "CH4_REDIS_CACHE") {
        $cacheStates = @("COLD_MISS", "MISS", "HIT")
    }

    foreach ($scenario in $scenarios) {
        foreach ($cacheState in $cacheStates) {
            $query = @{
                mode = $mode
                scenario = $scenario.name
            }
            if ($cacheState) {
                $query.cacheState = $cacheState
            }

            $queryString = ($query.GetEnumerator() | ForEach-Object {
                "{0}={1}" -f [System.Uri]::EscapeDataString($_.Key), [System.Uri]::EscapeDataString([string]$_.Value)
            }) -join "&"
            $url = "$BaseUrl/internal/benchmarks/charts?$queryString"
            $safeMode = Get-SafeName -Value $mode
            $safeScenario = Get-SafeName -Value $scenario.name
            $safeCacheState = if ($cacheState) { Get-SafeName -Value $cacheState } else { "NONE" }
            $caseId = "{0}__{1}__{2}" -f $safeMode, $safeScenario, $safeCacheState

            if ($mode -eq "CH4_REDIS_CACHE" -and $cacheState -eq "HIT") {
                $prewarmQuery = @{
                    mode = $mode
                    scenario = $scenario.name
                    cacheState = "MISS"
                }
                $prewarmQueryString = ($prewarmQuery.GetEnumerator() | ForEach-Object {
                    "{0}={1}" -f [System.Uri]::EscapeDataString($_.Key), [System.Uri]::EscapeDataString([string]$_.Value)
                }) -join "&"
                $prewarmUrl = "$BaseUrl/internal/benchmarks/charts?$prewarmQueryString"
                try {
                    Invoke-BenchmarkRequest -Url $prewarmUrl -TimeoutSeconds $RequestTimeoutSeconds | Out-Null
                } catch {
                    $failures += [PSCustomObject]@{
                        mode = $mode
                        scenario = $scenario.name
                        cacheState = $cacheState
                        phase = "prewarm"
                        iteration = 1
                        message = $_.Exception.Message
                    }
                    continue
                }
            }

            if ($CaptureMysqlStatusPerCase) {
                Invoke-BenchmarkHelperScript -FilePath .\benchmarks\scripts\collect_mysql_global_status.ps1 -Arguments @(
                    "-RunDir", $mysqlStatusDir,
                    "-Phase", ("{0}__before" -f $caseId)
                )
            }

            for ($i = 0; $i -lt $WarmupCount; $i++) {
                try {
                    Invoke-BenchmarkRequest -Url $url -TimeoutSeconds $RequestTimeoutSeconds | Out-Null
                } catch {
                    $failures += [PSCustomObject]@{
                        mode = $mode
                        scenario = $scenario.name
                        cacheState = $cacheState
                        phase = "warmup"
                        iteration = $i + 1
                        message = $_.Exception.Message
                    }
                }
            }

            $samples = @()
            for ($i = 0; $i -lt $MeasureCount; $i++) {
                try {
                    $sample = Invoke-BenchmarkRequest -Url $url -TimeoutSeconds $RequestTimeoutSeconds
                    $samples += [PSCustomObject]@{
                        iteration = $i + 1
                        wallMillis = $sample.wallMillis
                        totalMillis = Get-TimingValue -Timings $sample.body.data.timings -Name "totalMillis"
                        searchMillis = Get-TimingValue -Timings $sample.body.data.timings -Name "searchMillis"
                        hydrateMillis = Get-TimingValue -Timings $sample.body.data.timings -Name "hydrateMillis"
                        lastUpdatedMillis = Get-TimingValue -Timings $sample.body.data.timings -Name "lastUpdatedMillis"
                        assembleMillis = Get-TimingValue -Timings $sample.body.data.timings -Name "assembleMillis"
                        cacheState = $sample.body.data.cacheState
                        scenario = $sample.body.data.scenario
                        entryCount = @($sample.body.data.result.entries).Count
                        raw = $sample.body
                    }
                } catch {
                    $failures += [PSCustomObject]@{
                        mode = $mode
                        scenario = $scenario.name
                        cacheState = $cacheState
                        phase = "measure"
                        iteration = $i + 1
                        message = $_.Exception.Message
                    }
                }
            }

            if ($CaptureMysqlStatusPerCase) {
                Invoke-BenchmarkHelperScript -FilePath .\benchmarks\scripts\collect_mysql_global_status.ps1 -Arguments @(
                    "-RunDir", $mysqlStatusDir,
                    "-Phase", ("{0}__after" -f $caseId)
                )
            }

            $rawPath = Join-Path $rawDir ("{0}__{1}__{2}.json" -f $mode, $scenario.name, $safeCacheState)
            $samples | ConvertTo-Json -Depth 10 | Set-Content -Path $rawPath -Encoding UTF8

            if ($samples.Count -gt 0) {
                $wallValues = @($samples | ForEach-Object { [double]$_.wallMillis })
                $totalValues = @($samples | ForEach-Object { [double]$_.totalMillis })

                $results += [PSCustomObject]@{
                    mode = $mode
                    scenario = $scenario.name
                    cacheState = $safeCacheState
                    count = $samples.Count
                    wallAvgMillis = [Math]::Round((($wallValues | Measure-Object -Average).Average), 2)
                    wallMedianMillis = [Math]::Round((Get-Median -Values $wallValues), 2)
                    wallP95Millis = [Math]::Round((Get-Percentile -Values $wallValues -Percentile 95), 2)
                    totalAvgMillis = [Math]::Round((($totalValues | Measure-Object -Average).Average), 2)
                    totalMedianMillis = [Math]::Round((Get-Median -Values $totalValues), 2)
                    totalP95Millis = [Math]::Round((Get-Percentile -Values $totalValues -Percentile 95), 2)
                    rawPath = $rawPath
                }
            }
        }
    }
}

$results | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $runDir "scenario_results.json") -Encoding UTF8
if ($failures.Count -eq 0) {
    "[]" | Set-Content -Path (Join-Path $runDir "failures.json") -Encoding UTF8
}
else {
    $failures | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $runDir "failures.json") -Encoding UTF8
}

Write-Host "Benchmark run completed:"
Write-Host "  Results: $runDir"
