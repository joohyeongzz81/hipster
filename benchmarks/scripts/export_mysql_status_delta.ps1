param(
    [string]$RunDir,
    [string[]]$CaseIds
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Load-Map {
    param([string]$Path)

    $json = Get-Content -Path $Path -Raw | ConvertFrom-Json
    $map = @{}
    foreach ($item in $json.values) {
        $map[$item.name] = [double]$item.value
    }
    return $map
}

foreach ($case in $CaseIds) {
    $before = Load-Map (Join-Path $RunDir ("mysql_status_{0}__before.json" -f $case))
    $after = Load-Map (Join-Path $RunDir ("mysql_status_{0}__after.json" -f $case))

    [PSCustomObject]@{
        case = $case
        Handler_read_rnd_next = $after["Handler_read_rnd_next"] - $before["Handler_read_rnd_next"]
        Handler_read_next = $after["Handler_read_next"] - $before["Handler_read_next"]
        Handler_read_key = $after["Handler_read_key"] - $before["Handler_read_key"]
        Sort_merge_passes = $after["Sort_merge_passes"] - $before["Sort_merge_passes"]
        Sort_rows = $after["Sort_rows"] - $before["Sort_rows"]
        Sort_scan = $after["Sort_scan"] - $before["Sort_scan"]
    }
}
