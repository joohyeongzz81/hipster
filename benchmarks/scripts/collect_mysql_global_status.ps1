param(
    [string]$RunDir,
    [string]$Phase = "snapshot"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunDir)) {
    throw "RunDir is required."
}

$outputPath = Join-Path $RunDir ("mysql_status_{0}.json" -f $Phase)
$mysqlCommand = @"
SHOW GLOBAL STATUS LIKE 'Handler_read%';
SHOW GLOBAL STATUS LIKE 'Sort%';
"@

$raw = docker exec hipster-mysql-master mysql -uroot -ppassword -D hipster -N -B -e $mysqlCommand

$entries = @()
foreach ($line in $raw) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }

    $parts = $line -split "`t", 2
    if ($parts.Count -ne 2) {
        continue
    }

    $entries += [PSCustomObject]@{
        name = $parts[0]
        value = $parts[1]
    }
}

$payload = [PSCustomObject]@{
    capturedAt = (Get-Date).ToString("o")
    phase = $Phase
    values = $entries
}

$payload | ConvertTo-Json -Depth 5 | Set-Content -Path $outputPath -Encoding UTF8

Write-Host "MySQL global status snapshot saved:"
Write-Host "  Output: $outputPath"
