param(
    [string]$EsBaseUrl = "http://localhost:9200",
    [string]$IndexName = "chart_scores_bench",
    [int]$MaxResultWindow = 50000
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$indexHealth = Invoke-RestMethod -Method Get -Uri "${EsBaseUrl}/_cat/indices/${IndexName}?h=index" -ErrorAction SilentlyContinue
if ([string]::IsNullOrWhiteSpace("$indexHealth")) {
    throw "Benchmark index '$IndexName' does not exist."
}

$settingsBody = @{
    index = @{
        max_result_window = $MaxResultWindow
        number_of_replicas = 0
        refresh_interval = "1s"
    }
} | ConvertTo-Json -Depth 5 -Compress

$response = Invoke-RestMethod -Method Put `
    -Uri "${EsBaseUrl}/${IndexName}/_settings" `
    -ContentType "application/json" `
    -Body $settingsBody

Write-Host "Ensured benchmark ES index settings:"
Write-Host ($response | ConvertTo-Json -Compress)
