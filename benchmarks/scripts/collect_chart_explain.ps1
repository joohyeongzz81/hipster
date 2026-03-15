param(
    [string]$BaseUrl = "http://localhost:18080",
    [string]$RunDir,
    [string[]]$ScenarioNames = @(),
    [string[]]$Modes = @(
        "CH1_JOIN_BASELINE",
        "CH2_DENORM_READ_MODEL",
        "CH3_QUERY_REWRITE_INDEXED",
        "CH4_REDIS_CACHE",
        "CH5_ELASTICSEARCH_READ"
    ),
    [int]$RequestTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunDir)) {
    throw "RunDir is required."
}

$Modes = @($Modes | ForEach-Object { $_ -split "," } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$ScenarioNames = @($ScenarioNames | ForEach-Object { $_ -split "," } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

$scenarioFiles = Get-ChildItem -Path "src/main/resources/benchmarks/chart/scenarios" -Filter "*.json" |
    Sort-Object Name |
    ForEach-Object {
        $content = Get-Content -Path $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        [PSCustomObject]@{
            name = $content.name
        }
    }

if ($ScenarioNames.Count -gt 0) {
    $scenarioFiles = $scenarioFiles | Where-Object { $ScenarioNames -contains $_.name }
}

$explainDir = Join-Path $RunDir "explain"
New-Item -ItemType Directory -Force -Path $explainDir | Out-Null

foreach ($mode in $Modes) {
    foreach ($scenario in $scenarioFiles) {
        $query = @{
            mode = $mode
            scenario = $scenario.name
        }

        $queryString = ($query.GetEnumerator() | ForEach-Object {
            "{0}={1}" -f [System.Uri]::EscapeDataString($_.Key), [System.Uri]::EscapeDataString([string]$_.Value)
        }) -join "&"

        $url = "$BaseUrl/internal/benchmarks/charts/explain?$queryString"
        $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $RequestTimeoutSeconds
        $body = $response.Content | ConvertFrom-Json

        $safeMode = $mode -replace '[^A-Za-z0-9_\-]', '_'
        $safeScenario = $scenario.name -replace '[^A-Za-z0-9_\-]', '_'
        $jsonPath = Join-Path $explainDir ("{0}__{1}.json" -f $safeMode, $safeScenario)
        $txtPath = Join-Path $explainDir ("{0}__{1}.txt" -f $safeMode, $safeScenario)

        $body | ConvertTo-Json -Depth 10 | Set-Content -Path $jsonPath -Encoding UTF8
        ($body.data.lines -join [Environment]::NewLine) | Set-Content -Path $txtPath -Encoding UTF8
    }
}

Write-Host "Explain collection completed:"
Write-Host "  Output: $explainDir"
