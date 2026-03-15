param(
    [string]$BaseUrl = "http://localhost:18080",
    [string]$RepresentativeApiUrl = "http://localhost:18080/api/v1/charts?page=0&size=1",
    [string]$WriteMode = "LIGHT_STAGE_INSERT",
    [int]$ProjectionChunkSize = 2000,
    [int]$EsBatchSize = 20000,
    [int]$PollingIntervalSeconds = 1,
    [int]$ProgressIntervalSeconds = 10,
    [int]$ApiVisibilityTimeoutSeconds = 120,
    [int]$HttpTimeoutSeconds = 7200,
    [string]$OutputRoot = "benchmarks/runs"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-JsonRequest {
    param(
        [string]$Method,
        [string]$Url,
        [int]$TimeoutSeconds
    )

    $maxAttempts = 5
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $Url -Method $Method -UseBasicParsing -TimeoutSec $TimeoutSeconds
            return ($response.Content | ConvertFrom-Json)
        } catch {
            if ($attempt -eq $maxAttempts) {
                throw
            }

            Start-Sleep -Seconds 3
        }
    }
}

function Save-Json {
    param(
        [string]$Path,
        [object]$Value
    )

    $Value | ConvertTo-Json -Depth 30 | Set-Content -Path $Path -Encoding UTF8
}

function Parse-DateTime {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }

    return [datetime]::Parse($Value)
}

function New-ApiObservation {
    param(
        [string]$Url
    )

    $observedAt = Get-Date
    try {
        $response = Invoke-WebRequest -Uri $Url -Method Get -UseBasicParsing -TimeoutSec 30
        $body = $response.Content | ConvertFrom-Json
        $data = $body.data

        return [PSCustomObject]@{
            observedAt = $observedAt.ToString("o")
            httpStatus = [int]$response.StatusCode
            version = $data.version
            lastUpdated = $data.lastUpdated
            entryCount = @($data.entries).Count
            success = $true
            error = $null
        }
    } catch {
        return [PSCustomObject]@{
            observedAt = $observedAt.ToString("o")
            httpStatus = $null
            version = $null
            lastUpdated = $null
            entryCount = $null
            success = $false
            error = $_.Exception.Message
        }
    }
}

function Get-EsDocumentCount {
    param([string]$IndexName)

    if ([string]::IsNullOrWhiteSpace($IndexName)) {
        return 0
    }

    try {
        $response = Invoke-WebRequest -Uri ("http://localhost:9200/{0}/_count" -f $IndexName) -UseBasicParsing -TimeoutSec 30
        return [int64](($response.Content | ConvertFrom-Json).count)
    } catch {
        return 0
    }
}

function Write-RunProgress {
    param([string]$Message)

    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $Message)
}

function Find-ApiVisibleAt {
    param(
        [object[]]$Trace,
        [string]$ExpectedVersion,
        [string]$ExpectedLastUpdated,
        [datetime]$PublishedAt
    )

    if ([string]::IsNullOrWhiteSpace($ExpectedVersion) -or [string]::IsNullOrWhiteSpace($ExpectedLastUpdated)) {
        return $null
    }

    foreach ($entry in $Trace) {
        $observedAt = Parse-DateTime $entry.observedAt
        if ($null -eq $observedAt) {
            continue
        }

        if ($null -ne $PublishedAt -and $observedAt -lt $PublishedAt) {
            continue
        }

        if ($entry.version -eq $ExpectedVersion -and $entry.lastUpdated -eq $ExpectedLastUpdated) {
            return $entry.observedAt
        }
    }

    return $null
}

function Get-StateComponent {
    param(
        [object]$Snapshot,
        [string]$PropertyName
    )

    $property = $Snapshot.PSObject.Properties[$PropertyName]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function Get-OptionalPropertyValue {
    param(
        [object]$Object,
        [string]$PropertyName
    )

    if ($null -eq $Object) {
        return $null
    }

    $property = $Object.PSObject.Properties[$PropertyName]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function Build-PrecheckSummary {
    param([object]$Snapshot)

    $rows = $Snapshot.rowCounts
    return [ordered]@{
        capturedAt = $Snapshot.capturedAt
        publishEnabled = $Snapshot.publishEnabled
        appBenchmarkHealth = $Snapshot.appBenchmarkHealth
        redisHealth = $Snapshot.redisHealth
        elasticsearchHealth = $Snapshot.elasticsearchHealth
        checklist = [ordered]@{
            releasesCount = $rows.releases
            chartScoresCount = $rows.chartScores
            releaseRatingSummaryCount = $rows.releaseRatingSummary
            multiGenreRows = $rows.multiGenreRows
            currentVersion = $Snapshot.publishState.currentVersion
            previousVersion = $Snapshot.publishState.previousVersion
            aliasTarget = $Snapshot.esAlias.aliasTarget
            redisPublishedVersion = $Snapshot.redis.publishedVersion
            redisLastUpdated = $Snapshot.redis.lastUpdated
        }
        rawSnapshot = $Snapshot
    }
}

$timestamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$runDir = Join-Path $OutputRoot "full_run_$timestamp"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$meta = [ordered]@{
    startedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    representativeApiUrl = $RepresentativeApiUrl
    fullBatchBenchmark = [ordered]@{
        writeMode = $WriteMode
        projectionChunkSize = $ProjectionChunkSize
        esBatchSize = $EsBatchSize
    }
    publishEndToEnd = [ordered]@{
        pollingIntervalSeconds = $PollingIntervalSeconds
        progressIntervalSeconds = $ProgressIntervalSeconds
        apiVisibilityTimeoutSeconds = $ApiVisibilityTimeoutSeconds
        successCondition = [ordered]@{
            currentVersion = "expectedVersion"
            lastUpdated = "logical_as_of_at"
        }
    }
    roughEstimate = [ordered]@{
        projectionEstimatedMinutes = 14.2917
        esEstimatedMinutes = 6.9854
        publishEstimatedMinutes = 0.0001
        totalEstimatedMinutes = 21.2771
    }
}
Save-Json (Join-Path $runDir "meta.json") $meta

$initialPrecheckPayload = Invoke-JsonRequest -Method Get -Url "$BaseUrl/internal/benchmarks/chart-batch/precheck" -TimeoutSeconds 120
$initialPrecheck = $initialPrecheckPayload.data
if (-not $initialPrecheck.publishEnabled) {
    throw "chart.publish.enabled=false on benchmark app. B run would execute the wrong path."
}

$needsBootstrap = $null -eq $initialPrecheck.publishState -or [string]::IsNullOrWhiteSpace($initialPrecheck.publishState.currentVersion)
if ($needsBootstrap) {
    $bootstrapPayload = Invoke-JsonRequest -Method Post -Url "$BaseUrl/internal/benchmarks/chart-batch/bootstrap-current-state" -TimeoutSeconds 300
    Save-Json (Join-Path $runDir "bootstrap_publish_state.json") $bootstrapPayload.data
}

$precheckPayload = Invoke-JsonRequest -Method Get -Url "$BaseUrl/internal/benchmarks/chart-batch/precheck" -TimeoutSeconds 120
$precheck = $precheckPayload.data
Save-Json (Join-Path $runDir "publish_state_before.json") (Get-StateComponent -Snapshot $precheck -PropertyName "publishState")
Save-Json (Join-Path $runDir "redis_before.json") (Get-StateComponent -Snapshot $precheck -PropertyName "redis")
Save-Json (Join-Path $runDir "es_alias_before.json") (Get-StateComponent -Snapshot $precheck -PropertyName "esAlias")
Save-Json (Join-Path $runDir "precheck_summary.json") (Build-PrecheckSummary -Snapshot $precheck)

$expectedTotalRows = [int64]$precheck.rowCounts.releaseRatingSummary
$benchmarkIndexName = ("chart_scores_bench_full_bench_{0}" -f (Get-Date -Format "yyyyMMddHHmmss"))
$aUrl = "$BaseUrl/internal/benchmarks/chart-batch/full-run?writeMode=$WriteMode&projectionChunkSize=$ProjectionChunkSize&esBatchSize=$EsBatchSize&benchmarkIndexName=$benchmarkIndexName"
$aJob = Start-Job -ScriptBlock {
    param($Url, $TimeoutSeconds)

    try {
        $response = Invoke-WebRequest -Uri $Url -Method Post -UseBasicParsing -TimeoutSec $TimeoutSeconds
        $response.Content
    } catch {
        ([ordered]@{
            error = $true
            message = $_.Exception.Message
        } | ConvertTo-Json -Depth 10)
    }
} -ArgumentList $aUrl, $HttpTimeoutSeconds

Write-RunProgress ("A started. benchmarkIndex={0}" -f $benchmarkIndexName)
$lastAProgressAt = (Get-Date).AddSeconds(-1 * $ProgressIntervalSeconds)
while ($true) {
    $aJobState = (Get-Job -Id $aJob.Id).State
    if ($aJobState -ne "Running" -and $aJobState -ne "NotStarted") {
        break
    }

    if (((Get-Date) - $lastAProgressAt).TotalSeconds -ge $ProgressIntervalSeconds) {
        $progressSnapshot = (Invoke-JsonRequest -Method Get -Url "$BaseUrl/internal/benchmarks/chart-batch/precheck" -TimeoutSeconds 120).data
        $lightStageRows = [int64]$progressSnapshot.rowCounts.chartScoresLightStageBench
        $benchmarkDocCount = Get-EsDocumentCount -IndexName $benchmarkIndexName

        if ($lightStageRows -lt $expectedTotalRows) {
            $projectionPct = if ($expectedTotalRows -gt 0) { [Math]::Round(($lightStageRows / $expectedTotalRows) * 100.0, 2) } else { 0 }
            Write-RunProgress ("A projection in progress. stageRows={0}/{1} ({2}%)" -f $lightStageRows, $expectedTotalRows, $projectionPct)
        } elseif ($benchmarkDocCount -lt $expectedTotalRows) {
            $esPct = if ($expectedTotalRows -gt 0) { [Math]::Round(($benchmarkDocCount / $expectedTotalRows) * 100.0, 2) } else { 0 }
            Write-RunProgress ("A ES indexing in progress. benchmarkDocs={0}/{1} ({2}%)" -f $benchmarkDocCount, $expectedTotalRows, $esPct)
        } else {
            Write-RunProgress "A finalizing cache/publish-overhead measurement."
        }

        $lastAProgressAt = Get-Date
    }

    Start-Sleep -Seconds 2
}

$aResultJson = Receive-Job -Id $aJob.Id -Wait
Remove-Job -Id $aJob.Id -Force | Out-Null
$aResult = $aResultJson | ConvertFrom-Json
$aError = Get-OptionalPropertyValue -Object $aResult -PropertyName "error"
if ($aError) {
    throw "full-run request failed: $($aResult.message)"
}

$aRun = $aResult.data
Save-Json (Join-Path $runDir "a_full_batch_run.json") $aRun

$publishRunUrl = "$BaseUrl/internal/benchmarks/chart-batch/publish-run"
$publishJob = Start-Job -ScriptBlock {
    param($Url, $TimeoutSeconds)

    try {
        $response = Invoke-WebRequest -Uri $Url -Method Post -UseBasicParsing -TimeoutSec $TimeoutSeconds
        $response.Content
    } catch {
        ([ordered]@{
            error = $true
            message = $_.Exception.Message
        } | ConvertTo-Json -Depth 10)
    }
} -ArgumentList $publishRunUrl, $HttpTimeoutSeconds

$apiTrace = New-Object System.Collections.Generic.List[object]
$publishRun = $null
$expectedVersion = $null
$expectedLastUpdated = $null
$publishedAt = $null
$apiVisibleAt = $null
$publishTraceDeadline = $null
$lastBProgressAt = (Get-Date).AddSeconds(-1 * $ProgressIntervalSeconds)

Write-RunProgress "B started. polling chart API and publish state."

while ($true) {
    $observation = New-ApiObservation -Url $RepresentativeApiUrl
    $apiTrace.Add($observation) | Out-Null

    if (((Get-Date) - $lastBProgressAt).TotalSeconds -ge $ProgressIntervalSeconds) {
        $progressSnapshot = (Invoke-JsonRequest -Method Get -Url "$BaseUrl/internal/benchmarks/chart-batch/precheck" -TimeoutSeconds 120).data
        $publishState = $progressSnapshot.publishState
        $stageRows = [int64]$progressSnapshot.rowCounts.chartScoresStage
        $candidateIndexName = if ($null -ne $publishState) { $publishState.candidateEsIndexRef } else { $null }
        $candidateDocCount = Get-EsDocumentCount -IndexName $candidateIndexName

        if ($null -eq $publishState) {
            Write-RunProgress "B waiting for publish state initialization."
        } elseif ($publishState.status -eq "GENERATING" -or $publishState.status -eq "VALIDATING") {
            $projectionPct = if ($expectedTotalRows -gt 0) { [Math]::Round(($stageRows / $expectedTotalRows) * 100.0, 2) } else { 0 }
            Write-RunProgress ("B projection/validation in progress. status={0}, stageRows={1}/{2} ({3}%), candidateVersion={4}" -f $publishState.status, $stageRows, $expectedTotalRows, $projectionPct, $publishState.candidateVersion)
        } elseif ($publishState.status -eq "PUBLISHING") {
            Write-RunProgress ("B publishing in progress. currentVersion={0}, candidateVersion={1}, candidateDocs={2}" -f $publishState.currentVersion, $publishState.candidateVersion, $candidateDocCount)
        } elseif ($null -ne $publishState.candidateVersion -and $candidateDocCount -gt 0 -and $candidateDocCount -lt $expectedTotalRows) {
            $esPct = if ($expectedTotalRows -gt 0) { [Math]::Round(($candidateDocCount / $expectedTotalRows) * 100.0, 2) } else { 0 }
            Write-RunProgress ("B ES candidate indexing in progress. candidateDocs={0}/{1} ({2}%), candidateVersion={3}" -f $candidateDocCount, $expectedTotalRows, $esPct, $publishState.candidateVersion)
        } else {
            Write-RunProgress ("B state={0}, currentVersion={1}, previousVersion={2}" -f $publishState.status, $publishState.currentVersion, $publishState.previousVersion)
        }

        $lastBProgressAt = Get-Date
    }

    $jobState = (Get-Job -Id $publishJob.Id).State
    if ($null -eq $publishRun -and $jobState -ne "Running" -and $jobState -ne "NotStarted") {
        $jobResultJson = Receive-Job -Id $publishJob.Id -Wait
        Remove-Job -Id $publishJob.Id -Force | Out-Null
        $jobResult = $jobResultJson | ConvertFrom-Json
        $publishError = Get-OptionalPropertyValue -Object $jobResult -PropertyName "error"
        if ($publishError) {
            throw "publish-run request failed: $($jobResult.message)"
        }

        $publishRun = $jobResult.data
        $expectedVersion = $publishRun.publishState.currentVersion
        $expectedLastUpdated = $publishRun.publishState.logicalAsOfAt
        $publishedAt = Parse-DateTime $publishRun.publishState.publishedAt
        if ($null -ne $publishedAt) {
            $publishTraceDeadline = $publishedAt.AddSeconds($ApiVisibilityTimeoutSeconds)
        }

        $apiVisibleAt = Find-ApiVisibleAt -Trace $apiTrace.ToArray() -ExpectedVersion $expectedVersion -ExpectedLastUpdated $expectedLastUpdated -PublishedAt $publishedAt
        if ($null -ne $apiVisibleAt) {
            Write-RunProgress ("B API visibility confirmed at {0}" -f $apiVisibleAt)
            break
        }

        Write-RunProgress ("B publish completed. publishedAt={0}, expectedVersion={1}, logicalAsOfAt={2}" -f $publishRun.publishState.publishedAt, $expectedVersion, $expectedLastUpdated)
    }

    if ($null -ne $publishRun) {
        $apiVisibleAt = Find-ApiVisibleAt -Trace $apiTrace.ToArray() -ExpectedVersion $expectedVersion -ExpectedLastUpdated $expectedLastUpdated -PublishedAt $publishedAt
        if ($null -ne $apiVisibleAt) {
            Write-RunProgress ("B API visibility confirmed at {0}" -f $apiVisibleAt)
            break
        }

        if ($null -ne $publishTraceDeadline -and (Get-Date) -gt $publishTraceDeadline) {
            break
        }
    }

    Start-Sleep -Seconds $PollingIntervalSeconds
}

Save-Json (Join-Path $runDir "api_visibility_trace.json") $apiTrace.ToArray()
Save-Json (Join-Path $runDir "b_publish_run.json") $publishRun

$afterPayload = Invoke-JsonRequest -Method Get -Url "$BaseUrl/internal/benchmarks/chart-batch/precheck" -TimeoutSeconds 120
$after = $afterPayload.data
Save-Json (Join-Path $runDir "publish_state_after.json") (Get-StateComponent -Snapshot $after -PropertyName "publishState")
Save-Json (Join-Path $runDir "redis_after.json") (Get-StateComponent -Snapshot $after -PropertyName "redis")
Save-Json (Join-Path $runDir "es_alias_after.json") (Get-StateComponent -Snapshot $after -PropertyName "esAlias")

$publishedAtString = $publishRun.publishState.publishedAt
$apiVisibleAtDate = Parse-DateTime $apiVisibleAt
$publishToApiVisibleMillis = $null
if ($null -ne $publishedAt -and $null -ne $apiVisibleAtDate) {
    $publishToApiVisibleMillis = [Math]::Round(($apiVisibleAtDate - $publishedAt).TotalMilliseconds, 2)
}

$stepTimings = [ordered]@{
    aFullBatchBenchmarkRun = $aRun.stepTimings
    bPublishJobRun = $publishRun.stepTimings
}
Save-Json (Join-Path $runDir "step_timings.json") $stepTimings

$jobExecutionSummary = [ordered]@{
    aFullBatchBenchmarkRun = $aRun
    bPublishJobRun = $publishRun
}
Save-Json (Join-Path $runDir "job_execution_summary.json") $jobExecutionSummary

$aTotalMinutes = if ($aRun.totalBatchMillis) { [Math]::Round(([double]$aRun.totalBatchMillis / 1000.0 / 60.0), 4) } else { $null }
$bTotalMinutes = if ($publishRun.totalBatchMillis) { [Math]::Round(([double]$publishRun.totalBatchMillis / 1000.0 / 60.0), 4) } else { $null }
$roughTotalMinutes = [double]$meta.roughEstimate.totalEstimatedMinutes
$aDeltaMinutes = if ($null -ne $aTotalMinutes) { [Math]::Round(($aTotalMinutes - $roughTotalMinutes), 4) } else { $null }
$aDeltaPercent = if ($null -ne $aTotalMinutes -and $roughTotalMinutes -ne 0) { [Math]::Round((($aTotalMinutes - $roughTotalMinutes) / $roughTotalMinutes) * 100.0, 2) } else { $null }

$fullRunSummary = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    runDirectory = $runDir
    roughEstimate = $meta.roughEstimate
    aFullBatchBenchmarkRun = [ordered]@{
        jobStartedAt = $aRun.jobStartedAt
        jobEndedAt = $aRun.jobEndedAt
        totalBatchMillis = $aRun.totalBatchMillis
        totalBatchMinutes = $aTotalMinutes
        roughEstimateDeltaMinutes = $aDeltaMinutes
        roughEstimateDeltaPercent = $aDeltaPercent
        writeMode = $aRun.writeMode
        projectionChunkSize = $aRun.projectionChunkSize
        esBatchSize = $aRun.esBatchSize
        benchmarkIndexName = $aRun.benchmarkIndexName
        stepExecutionTotalMillis = $aRun.stepExecutionTotalMillis
        wallClockDiffMillis = $aRun.wallClockDiffMillis
        failureStep = $aRun.failureStep
        failureMessage = $aRun.failureMessage
        projectionMillis = ($aRun.stepTimings | Where-Object { $_.stepName -eq "chartScoreUpdateStep" } | Select-Object -First 1).executionMillis
        esIndexMillis = ($aRun.stepTimings | Where-Object { $_.stepName -eq "elasticsearchSyncStep" } | Select-Object -First 1).executionMillis
        cacheEvictionMillis = ($aRun.stepTimings | Where-Object { $_.stepName -eq "cacheEvictionStep" } | Select-Object -First 1).executionMillis
    }
    bPublishEndToEndRun = [ordered]@{
        jobStartedAt = $publishRun.jobStartedAt
        jobEndedAt = $publishRun.jobEndedAt
        totalBatchMillis = $publishRun.totalBatchMillis
        totalBatchMinutes = $bTotalMinutes
        publishedAt = $publishedAtString
        apiVisibleAt = $apiVisibleAt
        publishToApiVisibleMillis = $publishToApiVisibleMillis
        currentVersion = $publishRun.publishState.currentVersion
        previousVersion = $publishRun.publishState.previousVersion
        logicalAsOfAt = $publishRun.publishState.logicalAsOfAt
        apiVersion = if ($null -ne $apiVisibleAt) { ($apiTrace.ToArray() | Where-Object { $_.observedAt -eq $apiVisibleAt } | Select-Object -First 1).version } else { $null }
        apiLastUpdated = if ($null -ne $apiVisibleAt) { ($apiTrace.ToArray() | Where-Object { $_.observedAt -eq $apiVisibleAt } | Select-Object -First 1).lastUpdated } else { $null }
        redisPublishedVersion = $after.redis.publishedVersion
        redisLastUpdated = $after.redis.lastUpdated
        esAliasTarget = $after.esAlias.aliasTarget
        failureStep = $publishRun.failureStep
        failureMessage = $publishRun.failureMessage
    }
}
Save-Json (Join-Path $runDir "full_run_summary.json") $fullRunSummary

try {
    & powershell -ExecutionPolicy Bypass -File .\benchmarks\scripts\collect_app_benchmark_logs.ps1 -RunDir $runDir
    if ($LASTEXITCODE -ne 0) {
        throw "collect_app_benchmark_logs.ps1 exit code $LASTEXITCODE"
    }
} catch {
    Set-Content -Path (Join-Path $runDir "application.log") -Value ("Failed to collect docker logs: " + $_.Exception.Message) -Encoding UTF8
}

Write-Host "CH8 full run completed:"
Write-Host "  RunDir: $runDir"
Write-Host ("  A totalBatchMillis: {0}" -f $aRun.totalBatchMillis)
Write-Host ("  B totalBatchMillis: {0}" -f $publishRun.totalBatchMillis)
Write-Host ("  publishedAt: {0}" -f $publishedAtString)
Write-Host ("  apiVisibleAt: {0}" -f $apiVisibleAt)
