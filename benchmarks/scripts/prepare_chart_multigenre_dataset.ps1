param(
    [int]$MultiGenrePercent = 20,
    [string]$Seed = "chart-benchmark-g2-v1",
    [string]$OutputRoot = "benchmarks/prep"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($MultiGenrePercent -lt 0 -or $MultiGenrePercent -gt 100) {
    throw "MultiGenrePercent must be between 0 and 100."
}

$timestamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$runDir = Join-Path $OutputRoot "run_$timestamp"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

function Invoke-MySql {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql
    )

    $output = & docker exec `
        -e MYSQL_PWD=password `
        hipster-mysql-master `
        mysql `
        -uroot `
        -D hipster `
        -e $Sql

    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed: $output"
    }

    return $output
}

function Write-QueryResult {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Sql
    )

    $result = Invoke-MySql -Sql $Sql
    Set-Content -Path $Path -Value $result -Encoding UTF8
}

$beforeSql = @"
SELECT COUNT(*) AS total_rows FROM chart_scores;
SELECT JSON_LENGTH(genre_ids) AS json_length, COUNT(*) AS row_count
FROM chart_scores
GROUP BY JSON_LENGTH(genre_ids)
ORDER BY json_length;
SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(genre_ids, '$[0].id')) AS UNSIGNED) AS primary_genre,
       COUNT(*) AS row_count
FROM chart_scores
GROUP BY CAST(JSON_UNQUOTE(JSON_EXTRACT(genre_ids, '$[0].id')) AS UNSIGNED)
ORDER BY row_count DESC, primary_genre ASC;
"@

Write-QueryResult -Path (Join-Path $runDir "before_distribution.tsv") -Sql $beforeSql

$beforeReleaseGenresSql = @"
SELECT COUNT(*) AS total_rows FROM release_genres;
SELECT genre_id, COUNT(*) AS row_count
FROM release_genres
GROUP BY genre_id
ORDER BY row_count DESC, genre_id ASC;
"@

Write-QueryResult -Path (Join-Path $runDir "before_release_genres.tsv") -Sql $beforeReleaseGenresSql

$updateSql = @"
UPDATE chart_scores
SET genre_ids = CASE
    WHEN MOD(CRC32(CONCAT(release_id, ':$Seed')), 100) < $MultiGenrePercent THEN
        JSON_ARRAY(
            JSON_OBJECT(
                'id', CAST(JSON_UNQUOTE(JSON_EXTRACT(genre_ids, '$[0].id')) AS UNSIGNED),
                'isPrimary', true
            ),
            JSON_OBJECT(
                'id', MOD(CAST(JSON_UNQUOTE(JSON_EXTRACT(genre_ids, '$[0].id')) AS UNSIGNED) + 5, 10) + 1,
                'isPrimary', false
            )
        )
    ELSE
        JSON_ARRAY(
            JSON_OBJECT(
                'id', CAST(JSON_UNQUOTE(JSON_EXTRACT(genre_ids, '$[0].id')) AS UNSIGNED),
                'isPrimary', true
            )
        )
END;
"@

$updateOutput = Invoke-MySql -Sql $updateSql
Set-Content -Path (Join-Path $runDir "update_result.txt") -Value $updateOutput -Encoding UTF8

$afterSql = @"
SELECT COUNT(*) AS total_rows FROM chart_scores;
SELECT JSON_LENGTH(genre_ids) AS json_length, COUNT(*) AS row_count
FROM chart_scores
GROUP BY JSON_LENGTH(genre_ids)
ORDER BY json_length;
SELECT
    CAST(JSON_UNQUOTE(JSON_EXTRACT(genre_ids, '$[0].id')) AS UNSIGNED) AS primary_genre,
    CAST(JSON_UNQUOTE(JSON_EXTRACT(genre_ids, '$[1].id')) AS UNSIGNED) AS secondary_genre,
    COUNT(*) AS row_count
FROM chart_scores
WHERE JSON_LENGTH(genre_ids) >= 2
GROUP BY
    CAST(JSON_UNQUOTE(JSON_EXTRACT(genre_ids, '$[0].id')) AS UNSIGNED),
    CAST(JSON_UNQUOTE(JSON_EXTRACT(genre_ids, '$[1].id')) AS UNSIGNED)
ORDER BY row_count DESC, primary_genre ASC, secondary_genre ASC;
SELECT COUNT(*) AS g2_genres_1_7_count
FROM chart_scores
WHERE JSON_CONTAINS(genre_ids, JSON_OBJECT('id', 1))
  AND JSON_CONTAINS(genre_ids, JSON_OBJECT('id', 7));
"@

Write-QueryResult -Path (Join-Path $runDir "after_distribution.tsv") -Sql $afterSql

$syncReleaseGenresSql = @'
DELETE FROM release_genres;
ALTER TABLE release_genres AUTO_INCREMENT = 1;
INSERT INTO release_genres (created_at, is_primary, `order`, genre_id, release_id)
SELECT
    NOW(6) AS created_at,
    CASE WHEN jt.is_primary = 'true' THEN 1 ELSE 0 END AS is_primary,
    jt.ord - 1 AS `order`,
    jt.genre_id,
    cs.release_id
FROM chart_scores cs
JOIN JSON_TABLE(
    cs.genre_ids,
    '$[*]' COLUMNS (
        ord FOR ORDINALITY,
        genre_id BIGINT PATH '$.id',
        is_primary VARCHAR(5) PATH '$.isPrimary'
    )
) jt;
'@

$syncOutput = Invoke-MySql -Sql $syncReleaseGenresSql
Set-Content -Path (Join-Path $runDir "sync_release_genres_result.txt") -Value $syncOutput -Encoding UTF8

$afterReleaseGenresSql = @"
SELECT COUNT(*) AS total_rows FROM release_genres;
SELECT genre_id, COUNT(*) AS row_count
FROM release_genres
GROUP BY genre_id
ORDER BY row_count DESC, genre_id ASC;
SELECT
    rg_primary.genre_id AS primary_genre,
    rg_secondary.genre_id AS secondary_genre,
    COUNT(*) AS row_count
FROM release_genres rg_primary
JOIN release_genres rg_secondary
  ON rg_secondary.release_id = rg_primary.release_id
 AND rg_secondary.is_primary = b'0'
WHERE rg_primary.is_primary = b'1'
GROUP BY rg_primary.genre_id, rg_secondary.genre_id
ORDER BY row_count DESC, primary_genre ASC, secondary_genre ASC;
"@

Write-QueryResult -Path (Join-Path $runDir "after_release_genres.tsv") -Sql $afterReleaseGenresSql

$meta = [ordered]@{
    preparedAt = (Get-Date).ToString("o")
    seed = $Seed
    multiGenrePercent = $MultiGenrePercent
    outputDir = $runDir
    strategy = "Deterministic pseudo-random update using CRC32(release_id + seed); second genre = ((primary + 5) % 10) + 1"
    syncNormalizedSource = $true
    normalizedSource = "release_genres rebuilt from chart_scores.genre_ids via JSON_TABLE"
}

$meta | ConvertTo-Json | Set-Content -Path (Join-Path $runDir "meta.json") -Encoding UTF8

Write-Host "Prepared multi-genre chart_scores dataset:"
Write-Host "  RunDir: $runDir"
