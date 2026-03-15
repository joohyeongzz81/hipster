$ErrorActionPreference = "Stop"

docker compose -f docker-compose.yml -f docker-compose.benchmark.yml stop app-benchmark | Out-Null
docker compose -f docker-compose.yml -f docker-compose.benchmark.yml rm -f app-benchmark | Out-Null

Write-Host "Stopped benchmark app container."
